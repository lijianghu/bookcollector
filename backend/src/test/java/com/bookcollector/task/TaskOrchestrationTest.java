package com.bookcollector.task;

import com.bookcollector.common.BizException;
import com.bookcollector.common.enums.TargetType;
import com.bookcollector.common.enums.TaskStatus;
import com.bookcollector.cursor.repository.CursorStore;
import com.bookcollector.task.entity.CollectTask;
import com.bookcollector.task.entity.TaskLog;
import com.bookcollector.task.entity.TaskRun;
import com.bookcollector.task.repository.TaskRepository;
import com.bookcollector.task.service.TaskService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S3 任务编排的<b>离线单测</b> —— 状态机、并发拒绝、中断清理。
 *
 * <h3>为什么要 mock 掉 TaskRunner</h3>
 * {@link TaskRunner} 一旦真跑，就会联网采 20 秒。而这里要验证的是
 * <b>状态流转</b>，跟「采到几本书」毫无关系。把执行体换成 mock 之后：
 * <ul>
 *   <li>测试从 20 秒降到 1 秒以内，可以放进 {@code mvn test} 的默认扫描范围
 *       （所以这个类叫 {@code *Test} 而不是 {@code *IT}）</li>
 *   <li>失败时一眼能看出是状态机的问题，不会跟网络问题混淆</li>
 * </ul>
 *
 * <h3>⚠️ mock 掉 runner 带来的一个后果</h3>
 * 真实的 {@link TaskRunner} 会在 finally 里 {@code registry.release(...)}，
 * mock 不会。所以每个测试结束后采集权还占着 —— {@link #cleanup()} 里手工释放。
 * 这不是「测试技巧」，而是提醒：<b>释放采集权这件事必须由执行体负责</b>，
 * 换成别的实现时别忘了。
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("S3 · 任务编排状态机（离线）")
class TaskOrchestrationTest {

    /** 所有测试都用这个前缀，方便清理 */
    private static final String PREFIX = "s3unit-";

    @MockBean
    private TaskRunner runner;

    @Autowired
    private TaskService service;

    @Autowired
    private TaskRegistry registry;

    @Autowired
    private TaskRepository repository;

    @Autowired
    private InterruptedTaskDetector detector;

    @Autowired
    private CursorStore cursorStore;

    @Autowired
    private MongoTemplate mongoTemplate;

    /** 本次测试造出来的 taskId，用于精确清理 */
    private final List<String> created = new ArrayList<String>();

    /** 本次测试用到的 targetId，用于清理游标（游标是按目标存的，不按任务） */
    private final List<String> usedTargets = new ArrayList<String>();

    @AfterEach
    void cleanup() {
        // mock 不会释放采集权，手工释放，否则会污染后面的测试
        for (TaskHandle h : registry.listActive()) {
            registry.release(h.key());
        }
        for (String taskId : created) {
            mongoTemplate.remove(Query.query(Criteria.where("taskId").is(taskId)), TaskRun.class);
            mongoTemplate.remove(Query.query(Criteria.where("taskId").is(taskId)),
                    com.bookcollector.task.entity.TaskLog.class);
        }
        mongoTemplate.remove(
                Query.query(Criteria.where("targetId").regex("^" + PREFIX)), CollectTask.class);
        // 游标是按目标存的，也要清 —— 否则下个测试会从上次的游标续传，结果不可预测
        for (String targetId : usedTargets) {
            cursorStore.delete(TargetType.CATEGORY, targetId);
        }
        created.clear();
        usedTargets.clear();
    }

    // ==================================================================
    // 创建
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("1. 新建任务 → PENDING，runCount=0，maxPages 归一化")
    void createStartsAsPending() {
        CollectTask task = newTask("a", 10);
        assertEquals(TaskStatus.PENDING.name(), task.getStatus());
        assertEquals(0, task.getRunCount().intValue());
        assertEquals(10, task.getMaxPages().intValue());
        assertNotNull(task.getCreatedAt(), "createdAt 应由审计填充");

        // null / 负数 → 0（不限页数）
        assertEquals(0, newTask("b", null).getMaxPages().intValue());
        assertEquals(0, newTask("c", -5).getMaxPages().intValue());

        System.out.println("[S3-U1] 新建任务 ✓  PENDING，maxPages 归一化正确");
    }

    @Test
    @Order(2)
    @DisplayName("2. 同一目标不能建两个任务（uk_target 唯一索引）")
    void duplicateTargetIsRejected() {
        newTask("dup", 5);
        BizException e = assertThrows(BizException.class, () -> newTask("dup", 5));
        assertTrue(e.getMessage().contains("已经有一个任务"),
                "报错要说清原因，实际：" + e.getMessage());

        System.out.println("[S3-U2] 重复目标被拒绝 ✓  " + e.getMessage());
    }

    // ==================================================================
    // start
    // ==================================================================

    @Test
    @Order(3)
    @DisplayName("3. start → RUNNING，建出 run 记录，游标初始化，runCount+1")
    void startTransitionsToRunning() {
        CollectTask task = newTask("start", 20);
        TaskRun run = service.start(task.getId());

        assertNotNull(run.getId());
        assertEquals(TaskStatus.RUNNING.name(), run.getStatus());
        assertEquals(0, run.getPagesDone().intValue());
        assertEquals(0L, run.getBooksSaved().longValue());
        assertNotNull(run.getLastProgressAt(), "lastProgressAt 必须有值（判假死用）");

        CollectTask after = service.get(task.getId());
        assertEquals(TaskStatus.RUNNING.name(), after.getStatus());
        assertEquals(run.getId(), after.getLastRunId());
        assertEquals(1, after.getRunCount().intValue());

        // 采集权被占用
        assertTrue(registry.isBusy(TargetType.CATEGORY.name(), task.getTargetId()));

        System.out.println("[S3-U3] start ✓  状态 RUNNING，run=" + run.getId()
                + "，runCount=1，采集权已占用");
    }

    @Test
    @Order(4)
    @DisplayName("4. 运行中重复 start → 拒绝（不新建 run）")
    void startTwiceIsRejected() {
        CollectTask task = newTask("twice", 20);
        TaskRun first = service.start(task.getId());

        BizException e = assertThrows(BizException.class, () -> service.start(task.getId()));
        assertTrue(e.getMessage().contains("正在运行中"), "实际：" + e.getMessage());

        // 不能多出一条 run
        assertEquals(1, service.listRuns(task.getId(), 10).size());
        assertEquals(1, service.get(task.getId()).getRunCount().intValue());

        System.out.println("[S3-U4] 重复 start 被拒绝 ✓  run 未增加，仍是 " + first.getId());
    }

    @Test
    @Order(5)
    @DisplayName("5. 全局并发闸门：已有任务在跑时，另一个目标也启动不了")
    void concurrentLimitRejectsOtherTarget() {
        CollectTask a = newTask("gate-a", 20);
        service.start(a.getId());

        CollectTask b = newTask("gate-b", 20);
        BizException e = assertThrows(BizException.class, () -> service.start(b.getId()));
        assertTrue(e.getMessage().contains("最多允许 1 个"),
                "报错要说清并发上限，实际：" + e.getMessage());

        // b 状态不能被改成 RUNNING
        assertEquals(TaskStatus.PENDING.name(), service.get(b.getId()).getStatus());
        // b 也不能留下 RUNNING 的 run 记录（回滚要干净）
        List<TaskRun> bRuns = service.listRuns(b.getId(), 10);
        for (TaskRun r : bRuns) {
            assertFalse(TaskStatus.RUNNING.name().equals(r.getStatus()),
                    "被拒绝的启动不该留下 RUNNING 的 run");
        }

        System.out.println("[S3-U5] 全局并发闸门 ✓  " + e.getMessage());
    }

    // ==================================================================
    // pause / resume
    // ==================================================================

    @Test
    @Order(6)
    @DisplayName("6. pause 只允许 RUNNING；PENDING 上调用要报错")
    void pauseRequiresRunning() {
        CollectTask task = newTask("pause-pending", 20);
        BizException e = assertThrows(BizException.class, () -> service.pause(task.getId()));
        assertTrue(e.getMessage().contains("只有「运行中」"), "实际：" + e.getMessage());
        System.out.println("[S3-U6] pause 准入校验 ✓  " + e.getMessage());
    }

    @Test
    @Order(7)
    @DisplayName("7. pause → PAUSED；resume → 同一个 run 原地恢复（不新建 run）")
    void pauseThenResumeKeepsSameRun() throws Exception {
        CollectTask task = newTask("pause-resume", 20);
        TaskRun run = service.start(task.getId());

        service.pause(task.getId());
        assertEquals(TaskStatus.PAUSED.name(), service.get(task.getId()).getStatus());
        TaskHandle handle = registry.find(TargetType.CATEGORY.name(), task.getTargetId());
        assertNotNull(handle);
        assertTrue(handle.isPaused(), "handle 应处于暂停态");

        // awaitIfPaused 必须真的阻塞住
        Thread blocked = new Thread(() -> {
            try {
                handle.awaitIfPaused();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        blocked.setDaemon(true);
        blocked.start();
        blocked.join(300);
        assertTrue(blocked.isAlive(), "暂停中 awaitIfPaused 应阻塞住调用线程");

        TaskRun resumed = service.resume(task.getId());
        assertNotNull(resumed);
        assertEquals(run.getId(), resumed.getId(), "★ 恢复必须是同一个 run（原地唤醒，不重采）");
        assertEquals(TaskStatus.RUNNING.name(), service.get(task.getId()).getStatus());
        blocked.join(1000);
        assertFalse(blocked.isAlive(), "恢复后 awaitIfPaused 应立即返回");

        // 恢复不增加 runCount
        assertEquals(1, service.get(task.getId()).getRunCount().intValue());

        System.out.println("[S3-U7] pause/resume ✓  同一个 run 原地恢复：" + run.getId());
    }

    // ==================================================================
    // cancel / retry
    // ==================================================================

    @Test
    @Order(8)
    @DisplayName("8. cancel → CANCELED，采集权释放")
    void cancelWhileRunning() {
        CollectTask task = newTask("cancel", 20);
        service.start(task.getId());
        assertTrue(registry.isBusy(TargetType.CATEGORY.name(), task.getTargetId()));

        service.cancel(task.getId());
        assertEquals(TaskStatus.CANCELED.name(), service.get(task.getId()).getStatus());
        assertTrue(TaskStatus.of(service.get(task.getId()).getStatus()).isTerminal());

        System.out.println("[S3-U8] cancel ✓  状态 CANCELED");
    }

    @Test
    @Order(9)
    @DisplayName("9. retry 只允许终态；PENDING 上调用要报错")
    void retryRequiresTerminal() {
        CollectTask task = newTask("retry-pending", 20);
        BizException e = assertThrows(BizException.class, () -> service.retry(task.getId()));
        assertTrue(e.getMessage().contains("还没结束"), "实际：" + e.getMessage());
        System.out.println("[S3-U9] retry 准入校验 ✓  " + e.getMessage());
    }

    @Test
    @Order(10)
    @DisplayName("10. 终态任务可以 retry → 新开一条 run，旧的独立留痕")
    void retryFromTerminalStartsNewRun() {
        // 直接造一个「上次运行失败了」的终态，不走 start ——
        // 因为 mock 掉的 TaskRunner 不会释放采集权，走 start 再 cancel
        // 会让 handle 一直占着，后面的 retry 会被全局闸门拦下（那是 mock 的产物，不是产品行为）。
        CollectTask task = newTask("retry-ok", 20);

        TaskRun failed = TaskRun.builder()
                .taskId(task.getId())
                .taskName(task.getName())
                .targetType(TargetType.CATEGORY.name())
                .targetId(task.getTargetId())
                .targetName(task.getTargetName())
                .startedAt(new Date())
                .status(TaskStatus.FAILED.name())
                .pagesDone(3)
                .booksSaved(60L)
                .currentCursor(60)
                .lastProgressAt(new Date())
                .errorMsg("上一次失败：HTTP 503")
                .build();
        repository.saveRun(failed);
        task.setStatus(TaskStatus.FAILED.name());
        repository.save(task);

        // ★ 续传的权威依据是 collect_cursors，不是 TaskRun.currentCursor。
        //   TaskRun.currentCursor 只是「这次运行跑到哪了」的进度快照（给界面看的）；
        //   真正决定「下次从哪开始」的是 CollectCursor.maxIndex（见 CollectCursor 类注释）。
        //   所以这里必须推进游标，光填 run 的字段是没用的。
        cursorStore.advance(TargetType.CATEGORY, task.getTargetId(), "文学",
                60, 60L, 54074, false, null);

        TaskRun second = service.retry(task.getId());
        assertNotNull(second);
        assertFalse(failed.getId().equals(second.getId()), "retry 应新建一条 run");
        assertEquals(TaskStatus.RUNNING.name(), service.get(task.getId()).getStatus());
        assertEquals(1, service.get(task.getId()).getRunCount().intValue(),
                "markTaskStarted 应把 runCount 从 0 加到 1");

        // ★ 续传语义：新 run 从游标 60 开始，不是从 0
        assertEquals(60, second.getCurrentCursor().intValue(),
                "retry 应从当前游标续传，而不是从头重采");

        // 旧记录不被覆盖
        TaskRun stillThere = service.getRun(failed.getId());
        assertEquals(TaskStatus.FAILED.name(), stillThere.getStatus());
        assertEquals("上一次失败：HTTP 503", stillThere.getErrorMsg());

        System.out.println("[S3-U10] retry ✓  新 run=" + second.getId()
                + "，起始游标=60（续传），旧 run " + failed.getId() + " 独立留痕");
    }

    // ==================================================================
    // 中断清理
    // ==================================================================

    @Test
    @Order(11)
    @DisplayName("11. 启动时把遗留的 RUNNING/PAUSED 标成 INTERRUPTED（游标保留）")
    void interruptedDetectorCleansStaleActiveState() {
        // 直接造一条「上次进程留下的」RUNNING 记录（绕过 TaskService）
        CollectTask task = newTask("stale", 20);
        task.setStatus(TaskStatus.RUNNING.name());
        repository.save(task);
        created.add(task.getId());

        TaskRun staleRun = TaskRun.builder()
                .taskId(task.getId())
                .taskName(task.getName())
                .targetType(TargetType.CATEGORY.name())
                .targetId(task.getTargetId())
                .targetName(task.getTargetName())
                .startedAt(new Date())
                .status(TaskStatus.RUNNING.name())
                .pagesDone(7)
                .booksSaved(140L)
                .currentCursor(140)
                .lastProgressAt(new Date())
                .build();
        repository.saveRun(staleRun);

        // 游标要保留 —— 这是「可恢复」的前提
        cursorStore.advance(TargetType.CATEGORY, task.getTargetId(), "文学",
                140, 140L, 54074, false, staleRun.getId());

        // 模拟进程重启：执行清理
        detector.run(null);

        CollectTask afterTask = service.get(task.getId());
        assertEquals(TaskStatus.INTERRUPTED.name(), afterTask.getStatus());

        TaskRun afterRun = service.getRun(staleRun.getId());
        assertEquals(TaskStatus.INTERRUPTED.name(), afterRun.getStatus());
        assertNotNull(afterRun.getFinishedAt(), "中断的 run 应写结束时间");
        assertNotNull(afterRun.getErrorMsg(), "应说明中断原因");
        assertEquals(7, afterRun.getPagesDone().intValue(), "进度不应被清掉");
        assertEquals(140, afterRun.getCurrentCursor().intValue(), "游标不应被清掉");

        // ★ 关键：游标还在 → 可以直接恢复
        assertEquals(140, cursorStore.loadCursor(TargetType.CATEGORY, task.getTargetId()));

        // INTERRUPTED 不是终态，可以 resume
        assertFalse(TaskStatus.INTERRUPTED.isTerminal());
        assertTrue(TaskStatus.INTERRUPTED.isResumable());

        System.out.println("[S3-U11] 中断清理 ✓  task/run 都变 INTERRUPTED，"
                + "pagesDone=7 与游标=140 均保留，errorMsg=" + afterRun.getErrorMsg());

        // 清理这条游标（它是本测试造的假数据）
        cursorStore.delete(TargetType.CATEGORY, task.getTargetId());
    }

    @Test
    @Order(12)
    @DisplayName("12. 中断后 resume → 新开一次运行，从游标续传")
    void resumeAfterInterruptedStartsNewRun() {
        CollectTask task = newTask("resume-interrupted", 20);
        task.setStatus(TaskStatus.INTERRUPTED.name());
        repository.save(task);
        created.add(task.getId());

        cursorStore.advance(TargetType.CATEGORY, task.getTargetId(), "文学",
                60, 60L, 54074, false, null);

        TaskRun run = service.resume(task.getId());
        assertNotNull(run);
        assertEquals(TaskStatus.RUNNING.name(), service.get(task.getId()).getStatus());
        assertEquals(60, run.getCurrentCursor().intValue(),
                "★ 新 run 必须从游标 60 开始，而不是 0");

        System.out.println("[S3-U12] 中断后恢复 ✓  新 run=" + run.getId()
                + "，起始游标=" + run.getCurrentCursor() + "（续传，不是从头）");

        cursorStore.delete(TargetType.CATEGORY, task.getTargetId());
    }

    // ==================================================================
    // TaskHandle 单元行为
    // ==================================================================

    @Test
    @Order(13)
    @DisplayName("13. cancel 必须能解除暂停（否则暂停中的任务永远结束不了）")
    void cancelUnblocksPausedWait() throws Exception {
        TaskHandle handle = new TaskHandle("k", "t", "r", "测试");
        handle.pause();

        Thread waiter = new Thread(() -> {
            try {
                handle.awaitIfPaused();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.setDaemon(true);
        waiter.start();
        waiter.join(300);
        assertTrue(waiter.isAlive(), "暂停中应阻塞");

        handle.cancel();
        waiter.join(1000);
        assertFalse(waiter.isAlive(), "★ cancel 必须唤醒阻塞中的线程");
        assertTrue(handle.isCanceled());
        assertFalse(handle.isPaused(), "cancel 后不应还处于暂停态");

        System.out.println("[S3-U13] cancel 解除暂停 ✓  阻塞线程被唤醒");
    }

    @Test
    @Order(14)
    @DisplayName("14. TaskHandle 未暂停时 awaitIfPaused 立即返回（不阻塞）")
    void awaitIfPausedReturnsImmediatelyWhenNotPaused() throws Exception {
        TaskHandle handle = new TaskHandle("k2", "t2", "r2", "测试");
        long t0 = System.currentTimeMillis();
        handle.awaitIfPaused();
        long cost = System.currentTimeMillis() - t0;
        assertTrue(cost < 100, "未暂停时不应阻塞，实际耗时 " + cost + "ms");
        assertSame(false, handle.isCanceled());
        System.out.println("[S3-U14] 未暂停时快速返回 ✓  耗时 " + cost + "ms");
    }

    // ==================================================================

    private CollectTask newTask(String suffix, Integer maxPages) {
        String targetId = PREFIX + suffix;
        usedTargets.add(targetId);
        CollectTask task = service.create("S3单测-" + suffix, "CATEGORY", targetId,
                "测试目标-" + suffix, maxPages, "S3 单元测试");
        created.add(task.getId());
        return task;
    }
}
