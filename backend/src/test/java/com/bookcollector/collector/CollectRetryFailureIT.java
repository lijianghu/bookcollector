package com.bookcollector.collector;

import com.bookcollector.audit.entity.ApiRequest;
import com.bookcollector.common.enums.TargetType;
import com.bookcollector.common.enums.TaskStatus;
import com.bookcollector.config.WereadProperties;
import com.bookcollector.cursor.CursorStore;
import com.bookcollector.task.TaskRegistry;
import com.bookcollector.task.TaskService;
import com.bookcollector.task.entity.CollectTask;
import com.bookcollector.task.entity.TaskLog;
import com.bookcollector.task.entity.TaskRun;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S7 端到端验收 —— <b>对端异常 / 断网时，任务必须失败得「说得清」</b>。
 *
 * <p>这是 S2 留下的那条验收（「故意断网 → 重试 3 次后任务 FAILED，日志有清晰原因」），
 * S2 只做到「{@code errorMsg} 装进 {@link CollectResult}」，
 * 「任务真的变 FAILED」要等 S3 的 {@code TaskRunner} 才成立 —— 所以顺延到 S7 一次验完。
 *
 * <h3>怎么验（不 mock 采集逻辑，只换对端）</h3>
 * 用 JDK 自带的 {@link HttpServer} 起一个「假微信读书」，通过
 * {@link DynamicPropertySource} 把 {@code bookcollector.weread.base-url} 指过去。
 * 好处是<b>能精确数出服务端收到了几次请求</b> —— 这是断言「重试了 3 次」唯一诚实的办法。
 * 换成 mock 掉 {@code WereadClient} 就变成「测自己写的 mock」了，没有意义。
 *
 * <table border="1">
 *   <tr><th>用例</th><th>对端行为</th><th>期望</th></tr>
 *   <tr><td>1</td><td>返回 500</td><td>重试满 {@code max-retry} 次 → FAILED，原因含 {@code HTTP 500}</td></tr>
 *   <tr><td>2</td><td>返回 404</td><td><b>只请求 1 次</b>（4xx 重试没有意义）→ FAILED，原因含 {@code HTTP 404}</td></tr>
 *   <tr><td>3</td><td>端口无人监听（真断网）</td><td>重试满 {@code max-retry} 次 → FAILED，原因含 {@code 网络异常}</td></tr>
 * </table>
 *
 * <h3>🔴 为什么用例 2 值得单独写</h3>
 * {@code CollectLoop.fetchWithRetry} 里那段「4xx 不重试」的分支，
 * 曾经因为 {@code RestTemplate} 的默认错误处理器会<b>先抛异常</b>而变成死代码 ——
 * 4xx 被包装成 {@code error != null} 的网络异常，于是照样退避 1s + 3s 白等 4 秒。
 * 这个用例就是钉住它：请求次数必须恰好是 1。
 *
 * <h3>⚠️ 用例 3 为什么断言「耗时」而不是「次数」</h3>
 * 端口没人监听时服务端收不到请求，数不出来。退而求其次：
 * 3 次尝试之间有 1s + 3s 的退避，而单次失败几百毫秒就返回，
 * 所以「总耗时 ≥ 4 秒」足以证明它真的重试了 —— 断言里写清了这条推理。
 *
 * <p>顺带一个实测到的细节：停掉假服务后<b>第一次</b>尝试报的是
 * {@code SocketException: Software caused connection abort: recv failed} 而不是
 * {@code Connection refused} —— 因为 Apache HttpClient 从连接池里拿了一条
 * 已经死掉的连接去用。第二次起才是干净的 {@code Connection refused}。
 * 两种都被归到「网络异常」，这正是我们要的：<b>不管怎么断的，用户看到的都是同一句人话</b>。
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("S7 · 对端异常/断网 → 重试 → 任务 FAILED（真实 HTTP，不 mock）")
class CollectRetryFailureIT {

    /** 退避序列 1s → 3s，两次间隔合计 4s（见 CollectLoop.BACKOFF_BASE_MS / BACKOFF_FACTOR） */
    private static final long BACKOFF_TOTAL_MS = 4_000L;

    /** 假服务监听的端口。取空闲端口，避免撞上本机别的东西 */
    private static final int FAKE_PORT = findFreePort();

    private static HttpServer fakeServer;

    /** 服务端实际收到的请求次数 —— 「重试了几次」的硬证据 */
    private static final AtomicInteger ATTEMPTS = new AtomicInteger();

    /** 假服务要返回的状态码 */
    private static volatile int fakeStatus = 500;

    private static final String TARGET_ID = "it-retry-target";
    private static final String TARGET_NAME = "S7-重试验收目标";

    @Autowired
    private TaskService service;

    @Autowired
    private TaskRegistry registry;

    @Autowired
    private CursorStore cursorStore;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private WereadProperties props;

    private String taskId;

    // ==================================================================
    // 把微信读书的 base-url 指到假服务上
    // ==================================================================

    @DynamicPropertySource
    static void pointWereadAtFakeServer(DynamicPropertyRegistry registry) {
        registry.add("bookcollector.weread.base-url",
                () -> "http://127.0.0.1:" + FAKE_PORT + "/web/bookListInCategory");
    }

    @BeforeAll
    static void startFakeServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", FAKE_PORT), 0);
        server.createContext("/web/bookListInCategory", exchange -> {
            ATTEMPTS.incrementAndGet();
            byte[] body = "{\"errCode\":-1,\"errMsg\":\"fake weread for IT\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(fakeStatus, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        fakeServer = server;
        System.out.println("[S7-IT] 假微信读书服务已启动：http://127.0.0.1:" + FAKE_PORT
                + "/web/bookListInCategory（base-url 已指向它，真实接口不会被访问）");
    }

    @AfterAll
    static void stopFakeServer() {
        if (fakeServer != null) {
            fakeServer.stop(0);
            fakeServer = null;
        }
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException("取不到空闲端口", e);
        }
    }

    // ==================================================================
    // 每个用例：干净的库状态 + 一个 PENDING 任务
    // ==================================================================

    @BeforeEach
    void setUp() {
        ATTEMPTS.set(0);
        clearTarget();
        CollectTask task = service.create("S7-IT-重试", "CATEGORY", TARGET_ID, TARGET_NAME, 5,
                "S7 端到端验收：对端异常 / 断网");
        taskId = task.getId();
    }

    @AfterEach
    void cleanup() throws Exception {
        // 失败在半路时把还在跑的收掉，别拖累后面的用例
        try {
            TaskStatus status = TaskStatus.of(service.get(taskId).getStatus());
            if (!status.isTerminal()) {
                service.cancel(taskId);
                awaitTaskTerminal(30_000);
            }
        } catch (Exception e) {
            System.out.println("[S7-IT] 清理时取消任务失败（可忽略）：" + e.getMessage());
        }
        awaitRegistryIdle(30_000);

        clearTarget();
        cursorStore.delete(TargetType.CATEGORY, TARGET_ID);
    }

    // ==================================================================
    // 用例 1：5xx → 重试满 3 次 → FAILED
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("1. 接口 5xx → 重试满 max-retry 次 → 任务 FAILED，原因说清是 HTTP 500")
    void http5xxRetriesThenFails() throws Exception {
        fakeStatus = 500;
        int expectedAttempts = Math.max(1, props.getMaxRetry());

        TaskRun run = service.start(taskId);
        TaskRun finished = awaitTerminal(run.getId());

        // ---- ★ 重试次数：服务端真的被打了 3 次 ----
        assertEquals(expectedAttempts, ATTEMPTS.get(),
                "★ 5xx 应重试满 " + expectedAttempts + " 次（max-retry=" + props.getMaxRetry()
                        + "），服务端实际收到 " + ATTEMPTS.get() + " 次请求");

        // ---- 任务终态 ----
        assertEquals(TaskStatus.FAILED.name(), finished.getStatus(),
                "5xx 采不到数据 → 任务应 FAILED，实际 " + finished.getStatus());
        assertEquals(TaskStatus.FAILED.name(), service.get(taskId).getStatus(),
                "任务定义的状态也要跟着变 FAILED");
        assertNotNull(finished.getFinishedAt(), "终态必须写结束时间");
        assertEquals(0, finished.getPagesDone().intValue(), "一页都没成功");
        assertEquals(0L, finished.getBooksSaved().longValue(), "不该写入任何书");

        // ---- ★ 原因要清晰：是 HTTP 500，不是含糊的「网络异常」----
        String errorMsg = finished.getErrorMsg();
        assertNotNull(errorMsg, "失败必须带 errorMsg");
        assertTrue(errorMsg.contains("HTTP 500"),
                "★ errorMsg 应指出是 HTTP 500，实际：" + errorMsg);
        assertTrue(errorMsg.contains("第 1 页"),
                "errorMsg 应指出失败发生在第几页，实际：" + errorMsg);

        // ---- 审计里也要能看到真实状态码（不是 -1）----
        List<ApiRequest> audits = findAudit(run.getId());
        assertEquals(1, audits.size(),
                "重试是「同一次取页」的内部行为，审计只该记 1 条（不是 3 条）");
        assertEquals(500, audits.get(0).getStatusCode().intValue(),
                "审计应记录真实状态码 500；记成 -1 说明被 RestTemplate 的异常包装吞掉了");
        assertTrue(audits.get(0).getResponseTimeMs() > 0, "耗时应 > 0");
        assertNotNull(audits.get(0).getErrorMsg(), "审计的「原因」一栏不该空着");
        assertTrue(audits.get(0).getErrorMsg().contains("HTTP 500"),
                "审计里的原因应与任务失败原因一致，实际：" + audits.get(0).getErrorMsg());

        // ---- 运行日志里要有一句人话 ----
        List<TaskLog> logs = service.listLogs(run.getId(), 200);
        assertTrue(logs.stream().anyMatch(l -> "ERROR".equals(l.getLevel())
                        && l.getMessage() != null && l.getMessage().contains("HTTP 500")),
                "运行日志应有一条 ERROR，且写明 HTTP 500。实际日志："
                        + summarize(logs));

        // ---- 采集权必须释放，否则这个目标永远起不来 ----
        assertFalse(registry.isBusy(TargetType.CATEGORY.name(), TARGET_ID),
                "★ 失败也要释放采集权");

        System.out.println("[S7-IT-1] 5xx → FAILED ✓  服务端收到 " + ATTEMPTS.get()
                + " 次请求，errorMsg=" + errorMsg
                + "，耗时 " + costMs(finished) + "ms");
    }

    // ==================================================================
    // 用例 2：4xx → 不重试，只打 1 次
    // ==================================================================

    @Test
    @Order(2)
    @DisplayName("2. 接口 4xx → 不重试（只打 1 次）→ FAILED，原因说清是 HTTP 404")
    void http4xxDoesNotRetry() throws Exception {
        fakeStatus = 404;

        TaskRun run = service.start(taskId);
        TaskRun finished = awaitTerminal(run.getId());

        assertEquals(1, ATTEMPTS.get(),
                "★ 4xx 重试没有意义，应只请求 1 次，实际打了 " + ATTEMPTS.get() + " 次"
                        + "（>1 说明「4xx 不重试」的分支没生效，白等退避时间）");

        assertEquals(TaskStatus.FAILED.name(), finished.getStatus());
        String errorMsg = finished.getErrorMsg();
        assertNotNull(errorMsg);
        assertTrue(errorMsg.contains("HTTP 404"),
                "★ errorMsg 应指出是 HTTP 404，实际：" + errorMsg);
        assertFalse(errorMsg.contains("网络异常"),
                "404 是服务端明确答复，不该被描述成「网络异常」，实际：" + errorMsg);

        List<ApiRequest> audits = findAudit(run.getId());
        assertEquals(1, audits.size(), "应只有 1 条审计");
        assertEquals(404, audits.get(0).getStatusCode().intValue(),
                "审计应记录真实状态码 404");

        // 4xx 不该白等退避：单次请求 + 落库，几秒内就该结束
        assertTrue(costMs(finished) < BACKOFF_TOTAL_MS,
                "4xx 不该等退避（" + BACKOFF_TOTAL_MS + "ms），实际耗时 " + costMs(finished) + "ms");

        System.out.println("[S7-IT-2] 4xx → 不重试 ✓  服务端只收到 " + ATTEMPTS.get()
                + " 次请求，errorMsg=" + errorMsg + "，耗时 " + costMs(finished) + "ms");
    }

    // ==================================================================
    // 用例 3：真断网（端口无人监听）
    // ==================================================================

    @Test
    @Order(3)
    @DisplayName("3. 断网（端口无人监听）→ 重试满 max-retry 次 → FAILED，原因说清是网络异常")
    void networkDownRetriesThenFails() throws Exception {
        // 停掉假服务 = 这个端口从此没人应答，等价于「后端/网络断了」
        stopFakeServer();
        int expectedAttempts = Math.max(1, props.getMaxRetry());

        TaskRun run = service.start(taskId);
        TaskRun finished = awaitTerminal(run.getId());

        assertEquals(TaskStatus.FAILED.name(), finished.getStatus(),
                "连不上接口 → 任务应 FAILED，实际 " + finished.getStatus());
        assertEquals(TaskStatus.FAILED.name(), service.get(taskId).getStatus());
        assertEquals(0, finished.getPagesDone().intValue());

        String errorMsg = finished.getErrorMsg();
        assertNotNull(errorMsg, "失败必须带 errorMsg");
        assertTrue(errorMsg.contains("网络异常"),
                "★ errorMsg 应说清是网络层问题，实际：" + errorMsg);

        // 端口没人监听时服务端收不到请求，数不出来 → 用退避耗时反证「确实重试了」。
        // 实测（2026-09-22）：3 次尝试总耗时约 8s = 4s 退避（1s+3s）+ 每次约 2s 的连接失败等待。
        // 只打 1 次的话连退避都不会经历，几百毫秒就结束了，所以 4s 这条线足以区分。
        long cost = costMs(finished);
        assertTrue(cost >= BACKOFF_TOTAL_MS,
                "★ 应重试满 " + expectedAttempts + " 次：只有真的重试了才会经历 "
                        + BACKOFF_TOTAL_MS + "ms 的退避（1s+3s），实际总耗时只有 " + cost + "ms");

        List<ApiRequest> audits = findAudit(run.getId());
        assertEquals(1, audits.size(), "审计只该记 1 条（重试是内部行为）");
        assertEquals(-1, audits.get(0).getStatusCode().intValue(),
                "请求根本没发出去，状态码应为 -1");
        assertNotNull(audits.get(0).getErrorMsg(), "审计要留下失败原因");

        assertFalse(registry.isBusy(TargetType.CATEGORY.name(), TARGET_ID), "失败也要释放采集权");

        System.out.println("[S7-IT-3] 断网 → FAILED ✓  重试满 " + expectedAttempts
                + " 次（由 " + cost + "ms ≥ " + BACKOFF_TOTAL_MS + "ms 的退避耗时反证），"
                + "errorMsg=" + errorMsg);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 轮询到运行记录进入终态 */
    private TaskRun awaitTerminal(String runId) throws Exception {
        long deadline = System.currentTimeMillis() + 90_000;
        TaskRun last = null;
        while (System.currentTimeMillis() < deadline) {
            last = service.getRun(runId);
            if (TaskStatus.of(last.getStatus()).isTerminal()) {
                return last;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("等待运行记录进入终态超时（90s），当前状态 "
                + (last == null ? "未知" : last.getStatus()));
    }

    /** 轮询到任务定义的状态进入终态（只在清理里用） */
    private void awaitTaskTerminal(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            last = service.get(taskId).getStatus();
            if (TaskStatus.of(last).isTerminal()) {
                return;
            }
            Thread.sleep(150);
        }
        System.out.println("[S7-IT] 警告：等待任务终态超时，当前 " + last);
    }

    private void awaitRegistryIdle(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!registry.isBusy(TargetType.CATEGORY.name(), TARGET_ID)) {
                return;
            }
            Thread.sleep(150);
        }
        System.out.println("[S7-IT] 警告：等待采集权释放超时");
    }

    private long costMs(TaskRun run) {
        if (run.getStartedAt() == null || run.getFinishedAt() == null) {
            return -1L;
        }
        return run.getFinishedAt().getTime() - run.getStartedAt().getTime();
    }

    private List<ApiRequest> findAudit(String runId) {
        Query q = Query.query(Criteria.where("runId").is(runId));
        q.with(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.ASC, "pageIndex"));
        return mongoTemplate.find(q, ApiRequest.class);
    }

    private String summarize(List<TaskLog> logs) {
        StringBuilder sb = new StringBuilder();
        for (TaskLog l : logs) {
            sb.append("\n    [").append(l.getLevel()).append("] ").append(l.getMessage());
        }
        return sb.toString();
    }

    /**
     * 清掉本目标的 任务 / 运行 / 日志。
     *
     * <p>和 {@code TaskControlIT} 一样，<b>日志必须一起删</b>：
     * 只删 task 和 run 会留下 runId 指向不存在 run 的孤儿日志。
     */
    private void clearTarget() {
        List<CollectTask> tasks = mongoTemplate.find(
                Query.query(Criteria.where("targetType").is(TargetType.CATEGORY.name())
                        .and("targetId").is(TARGET_ID)), CollectTask.class);
        for (CollectTask t : tasks) {
            mongoTemplate.remove(Query.query(Criteria.where("taskId").is(t.getId())), TaskLog.class);
        }
        mongoTemplate.remove(
                Query.query(Criteria.where("targetType").is(TargetType.CATEGORY.name())
                        .and("targetId").is(TARGET_ID)), CollectTask.class);
        mongoTemplate.remove(
                Query.query(Criteria.where("targetType").is(TargetType.CATEGORY.name())
                        .and("targetId").is(TARGET_ID)), TaskRun.class);
    }
}
