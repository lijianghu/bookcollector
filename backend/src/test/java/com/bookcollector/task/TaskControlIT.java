package com.bookcollector.task;

import com.bookcollector.audit.entity.ApiRequest;
import com.bookcollector.common.BizException;
import com.bookcollector.common.enums.TargetType;
import com.bookcollector.common.enums.TaskStatus;
import com.bookcollector.cursor.repository.CursorStore;
import com.bookcollector.task.entity.CollectTask;
import com.bookcollector.task.entity.TaskLog;
import com.bookcollector.task.entity.TaskRun;
import com.bookcollector.task.service.TaskService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S3 任务编排的<b>真实验收</b> —— 打真实接口，验证四个动作真的有效。
 *
 * <p>对齐 S3 验收标准：
 * <ol>
 *   <li>启动任务 → {@code collect_task_runs.pagesDone} 逐页增长</li>
 *   <li>暂停 → 当前页跑完后停住，<b>不再发新请求</b></li>
 *   <li>恢复 → 从暂停游标继续</li>
 *   <li>取消 → 状态 {@code CANCELED}，{@code api_requests} 不再新增</li>
 *   <li>同一目标重复启动 → 被拒绝，返回明确 message</li>
 * </ol>
 *
 * <h3>「不再发新请求」怎么测</h3>
 * 直接数 {@code api_requests} 的条数。暂停是<b>协作式</b>的 ——
 * 采集线程只在「分页循环开头」检查信号，所以暂停请求发出后
 * <b>最多还会多一条</b>（当前页在途）。断言写成 {@code <= 1} 而不是 {@code == 0}，
 * 这才是对实现诚实的断言；然后额外睡 3.5 秒确认它真的停住了（没有继续冒出来）。
 *
 * <h3>为什么每个测试都要重置游标</h3>
 * 文学分类有 54074 本、约 2704 页。如果不重置，游标可能停在很远的地方，
 * 测试要跑很久。重置到 0 后从第一页开始，确定性好。
 */
@SpringBootTest
@DisplayName("S3 · 任务编排验收（真实接口）")
class TaskControlIT {

    private static final String TARGET_ID = "300000";
    private static final String TARGET_NAME = "文学";

    /** 留够跑完「暂停 + 恢复 + 取消」全流程的页数 */
    private static final int MAX_PAGES = 50;

    @Autowired
    private TaskService service;

    @Autowired
    private TaskRegistry registry;

    @Autowired
    private CursorStore cursorStore;

    @Autowired
    private MongoTemplate mongoTemplate;

    private CollectTask task;

    @BeforeEach
    void setUp() {
        // 清掉可能残留的同目标任务（uk_target 是唯一索引）
        clearTarget();
        // 从第 0 页开始，测试才可控
        cursorStore.reset(TargetType.CATEGORY, TARGET_ID);
        task = service.create("S3-IT-文学", "CATEGORY", TARGET_ID, TARGET_NAME, MAX_PAGES,
                "S3 编排验收");
    }

    @AfterEach
    void cleanup() throws Exception {
        // 万一测试失败在半路，把还在跑的任务取消掉，别让它拖累后面的测试
        try {
            CollectTask current = service.get(task.getId());
            TaskStatus status = TaskStatus.of(current.getStatus());
            if (!status.isTerminal()) {
                service.cancel(task.getId());
                awaitStatus(TaskStatus.CANCELED, 30_000);
            }
        } catch (Exception e) {
            System.out.println("[S3-IT] 清理时取消任务失败（可忽略）：" + e.getMessage());
        }
        // 等线程真正退出，避免它继续写库
        awaitRegistryIdle(30_000);

        clearTarget();
        cursorStore.delete(TargetType.CATEGORY, TARGET_ID);
    }

    // ==================================================================
    // 主流程：启动 → 暂停 → 恢复 → 取消
    // ==================================================================

    @Test
    @DisplayName("1. 启动/暂停/恢复/取消 全流程")
    void fullLifecycle() throws Exception {
        // ---------- 启动 ----------
        TaskRun run = service.start(task.getId());
        assertNotNull(run.getId());
        assertEquals(TaskStatus.RUNNING.name(), status());
        assertEquals(0, run.getCurrentCursor().intValue(), "重置游标后应从 0 开始");
        assertTrue(registry.isBusy(TargetType.CATEGORY.name(), TARGET_ID), "采集权应被占用");

        // ---------- 验收 1：pagesDone 逐页增长 ----------
        int pagesAtStart = awaitPagesDone(run.getId(), 3, 90_000);
        System.out.println("[S3-IT-1] 启动 ✓  已采 " + pagesAtStart + " 页，"
                + "booksSaved=" + service.getRun(run.getId()).getBooksSaved()
                + "，currentCursor=" + service.getRun(run.getId()).getCurrentCursor());

        // 进度是逐页刷新的 —— 抽查两次，游标应单调递增
        int cursor1 = service.getRun(run.getId()).getCurrentCursor();
        int pages2 = awaitPagesDone(run.getId(), pagesAtStart + 2, 90_000);
        int cursor2 = service.getRun(run.getId()).getCurrentCursor();
        assertTrue(cursor2 > cursor1,
                "游标应随页数增长：" + cursor1 + " → " + cursor2);
        assertEquals(pages2, service.getRun(run.getId()).getPagesDone().intValue(),
                "run.pagesDone 应与轮询到的一致");

        // ---------- 验收 2：暂停后不再发新请求 ----------
        service.pause(task.getId());
        assertEquals(TaskStatus.PAUSED.name(), status());
        int pagesAtPause = service.getRun(run.getId()).getPagesDone();
        int cursorAtPause = service.getRun(run.getId()).getCurrentCursor();
        long auditAtPause = countAudit(run.getId());

        Thread.sleep(3_500);   // 至少 3 个限速周期，足够暴露「没停住」

        long auditAfterWait = countAudit(run.getId());
        assertTrue(auditAfterWait - auditAtPause <= 1,
                "★ 暂停后不应继续发请求：暂停时 " + auditAtPause + " 条 → 3.5 秒后 "
                        + auditAfterWait + " 条（最多允许 1 条在途）");

        // ⚠️ 这里必须和上面一样留 1 的容差，不能写成严格相等。
        // 暂停是「发信号」，不是「立刻掐断」—— 若 pause() 那一刻正好有 1 页在途，
        // 它会把请求发完、页解析完、pagesDone 加 1 才停。这是**正确行为**，不是没停住。
        // 更隐蔽的是：该页的 api_requests 记录在**发请求时**就已写入，
        // 所以 audit 的增量可能是 0，而 pagesDone 仍然是 +1 ——
        // 只查 audit 会漏判，只查 pagesDone 严格相等则会**偶发假红**（2026-09-23 实测踩到）。
        // 真正的「停住」由上面那条 audit 断言保证：限速约 1 req/s，没停住 3.5 秒会多出 3~4 条。
        int pagesAfterPause = service.getRun(run.getId()).getPagesDone();
        assertTrue(pagesAfterPause - pagesAtPause <= 1,
                "★ 暂停期间 pagesDone 不应继续增长：暂停时 " + pagesAtPause + " → 3.5 秒后 "
                        + pagesAfterPause + "（最多允许 1 页在途落地）");

        System.out.println("[S3-IT-2] 暂停 ✓  pagesDone=" + pagesAtPause + "→" + pagesAfterPause
                + "，游标=" + cursorAtPause + "，api_requests " + auditAtPause + " → "
                + auditAfterWait + "（未继续增长）");

        // ---------- 验收 3：恢复后从暂停游标继续 ----------
        TaskRun resumed = service.resume(task.getId());
        assertNotNull(resumed);
        assertEquals(run.getId(), resumed.getId(), "恢复应复用同一个 run（原地唤醒）");
        assertEquals(TaskStatus.RUNNING.name(), status());

        int pagesAfterResume = awaitPagesDone(run.getId(), pagesAtPause + 2, 90_000);
        int cursorAfterResume = service.getRun(run.getId()).getCurrentCursor();
        assertTrue(cursorAfterResume > cursorAtPause,
                "★ 恢复后游标应从暂停处继续前进：" + cursorAtPause + " → " + cursorAfterResume);

        System.out.println("[S3-IT-3] 恢复 ✓  同一 run 继续，pagesDone "
                + pagesAtPause + " → " + pagesAfterResume + "，游标 "
                + cursorAtPause + " → " + cursorAfterResume + "（未重采）");

        // ---------- 验收 4：取消后不再新增请求 ----------
        service.cancel(task.getId());
        awaitStatus(TaskStatus.CANCELED, 30_000);

        long auditAtCancel = countAudit(run.getId());
        Thread.sleep(3_000);
        long auditAfterCancel = countAudit(run.getId());
        assertTrue(auditAfterCancel - auditAtCancel <= 1,
                "★ 取消后不应再有新请求：取消时 " + auditAtCancel + " 条 → 3 秒后 "
                        + auditAfterCancel + " 条");

        TaskRun finished = service.getRun(run.getId());
        assertNotNull(finished.getFinishedAt(), "终态必须写结束时间");
        assertFalse(registry.isBusy(TargetType.CATEGORY.name(), TARGET_ID),
                "★ 任务结束后采集权必须被释放，否则该目标永远无法再启动");

        // 运行日志里应该能看到完整轨迹
        List<TaskLog> logs = service.listLogs(run.getId(), 200);
        assertTrue(logs.size() >= pagesAfterResume,
                "每页应至少一条运行日志，实际 " + logs.size());

        System.out.println("[S3-IT-4] 取消 ✓  状态 CANCELED，api_requests " + auditAtCancel
                + " → " + auditAfterCancel + "（未继续增长），采集权已释放，日志 "
                + logs.size() + " 条");
    }

    // ==================================================================
    // 验收 5：同一目标重复启动被拒绝
    // ==================================================================

    @Test
    @DisplayName("2. 运行中重复启动同一目标 → 拒绝，且报错说清原因")
    void duplicateStartIsRejected() throws Exception {
        TaskRun first = service.start(task.getId());
        awaitPagesDone(first.getId(), 2, 90_000);

        BizException e = assertThrows(BizException.class, () -> service.start(task.getId()));
        assertTrue(e.getMessage().contains("正在运行中"),
                "报错要说清是「正在运行中」，实际：" + e.getMessage());
        assertEquals(1, service.get(task.getId()).getRunCount().intValue(),
                "被拒绝的启动不应增加 runCount");
        assertEquals(1, service.listRuns(task.getId(), 10).size(),
                "被拒绝的启动不应新建 run");

        System.out.println("[S3-IT-5] 重复启动被拒绝 ✓  " + e.getMessage()
                + "（runCount 仍为 1）");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private String status() {
        return service.get(task.getId()).getStatus();
    }

    /** 轮询到 pagesDone 达到目标值，返回实际值 */
    private int awaitPagesDone(String runId, int target, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int last = -1;
        while (System.currentTimeMillis() < deadline) {
            TaskRun run = service.getRun(runId);
            last = run.getPagesDone() == null ? 0 : run.getPagesDone();
            TaskStatus st = TaskStatus.of(run.getStatus());
            if (st.isTerminal() && last < target) {
                throw new AssertionError("任务在只采了 " + last + " 页时就结束了（期望至少 "
                        + target + " 页），status=" + st + " errorMsg=" + run.getErrorMsg());
            }
            if (last >= target) {
                return last;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("等待 pagesDone >= " + target + " 超时，当前 " + last);
    }

    private void awaitStatus(TaskStatus expected, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            last = status();
            if (expected.name().equals(last)) {
                return;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("等待任务状态变为 " + expected + " 超时，当前 " + last);
    }

    private void awaitRegistryIdle(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!registry.isBusy(TargetType.CATEGORY.name(), TARGET_ID)) {
                return;
            }
            Thread.sleep(150);
        }
        System.out.println("[S3-IT] 警告：等待采集权释放超时");
    }

    private long countAudit(String runId) {
        return mongoTemplate.count(
                Query.query(Criteria.where("runId").is(runId)), ApiRequest.class);
    }

    /**
     * 清掉本目标的任务 / 运行 / 日志。
     *
     * <p>⚠️ 日志必须一起删。只删 task 和 run 会留下「孤儿日志」——
     * 它们的 {@code runId} 指向已经不存在的 run，界面上永远查不到、也永远不会被清理。
     * 第一次跑完 IT 之后库里攒了 19 条孤儿日志，才发现这里漏了。
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
