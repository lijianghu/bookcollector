package com.bookcollector.task.service.impl;

import com.bookcollector.collector.ProgressReporter;
import com.bookcollector.common.BizException;
import com.bookcollector.common.PageResult;
import com.bookcollector.common.ResultBean;
import com.bookcollector.common.enums.TargetType;
import com.bookcollector.common.enums.TaskStatus;
import com.bookcollector.config.TaskProperties;
import com.bookcollector.cursor.repository.CursorStore;
import com.bookcollector.task.TaskHandle;
import com.bookcollector.task.TaskRegistry;
import com.bookcollector.task.TaskRunner;
import com.bookcollector.task.entity.CollectTask;
import com.bookcollector.task.entity.TaskLog;
import com.bookcollector.task.entity.TaskRun;
import com.bookcollector.task.repository.TaskRepository;
import com.bookcollector.task.service.TaskService;
import com.bookcollector.util.PageQueryUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link TaskService} 的实现。
 *
 * <p>状态机全貌、四个动作的准入条件、状态写入的分工（本实现写中间态走 CAS、
 * {@link com.bookcollector.task.TaskRunner} 写终态无条件覆盖）见
 * {@link TaskService} 的类注释。这里只保留实现细节。
 */
@Service
public class TaskServiceImpl implements TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskServiceImpl.class);

    private final TaskRepository repository;
    private final TaskRegistry registry;
    private final TaskRunner runner;
    private final CursorStore cursorStore;
    private final ProgressReporter progress;
    private final TaskProperties properties;

    public TaskServiceImpl(TaskRepository repository,
                           TaskRegistry registry,
                           TaskRunner runner,
                           CursorStore cursorStore,
                           ProgressReporter progress,
                           TaskProperties properties) {
        this.repository = repository;
        this.registry = registry;
        this.runner = runner;
        this.cursorStore = cursorStore;
        this.progress = progress;
        this.properties = properties;
    }

    // ==================================================================
    // 增删查
    // ==================================================================

    /**
     * 新建任务。初始状态 {@code PENDING}。
     *
     * <p>注意 {@code collect_tasks} 上有 {@code (targetType, targetId)} 唯一索引 ——
     * <b>一个目标只能有一个任务</b>。想换参数就改这个任务，不要建第二个。
     * 这样「目标」和「任务」是 1:1，游标归属才不会有歧义。
     */
    @Override
    public CollectTask create(String name, String targetType, String targetId,
                              String targetName, Integer maxPages, String remark) {
        TargetType type = TargetType.of(targetType);
        if (targetId == null || targetId.trim().isEmpty()) {
            throw new BizException(ResultBean.code_warn, "targetId 不能为空");
        }
        String tid = targetId.trim();
        String displayName = (targetName == null || targetName.trim().isEmpty())
                ? tid : targetName.trim();
        String taskName = (name == null || name.trim().isEmpty())
                ? displayName + "-采集" : name.trim();

        CollectTask task = CollectTask.builder()
                .name(taskName)
                .targetType(type.name())
                .targetId(tid)
                .targetName(displayName)
                .maxPages(normalizeMaxPages(maxPages))
                .status(TaskStatus.PENDING.name())
                .remark(remark)
                .runCount(0)
                .build();
        try {
            CollectTask saved = repository.save(task);
            progress.info(null, saved.getId(), "任务已创建：「" + taskName + "」，目标 "
                    + displayName + "(" + tid + ")，最大页数 " + describeMaxPages(saved.getMaxPages()));
            return saved;
        } catch (DuplicateKeyException e) {
            throw BizException.duplicate("目标「" + displayName + "」已经有一个任务了，"
                    + "请直接使用它，或先删除旧任务");
        }
    }

    @Override
    public CollectTask get(String taskId) {
        return requireTask(taskId);
    }

    @Override
    public List<CollectTask> list() {
        return repository.findAll();
    }

    @Override
    public List<TaskRun> listRuns(String taskId, Integer limit) {
        return repository.findRunsByTaskId(taskId,
                limit == null || limit <= 0 ? properties.getRunListLimit() : limit);
    }

    @Override
    public TaskRun getRun(String runId) {
        TaskRun run = repository.findRunById(runId);
        if (run == null) {
            throw BizException.notFound("运行记录不存在：" + runId);
        }
        return run;
    }

    @Override
    public List<TaskLog> listLogs(String runId, Integer limit) {
        return repository.findLogsByRunId(runId,
                limit == null || limit <= 0 ? properties.getLogListLimit() : limit);
    }

    /**
     * 运行记录分页（FR-D5，跨任务）。
     *
     * <p>{@code taskId} / {@code status} 都为 null 时就是「全部运行记录」。
     * 页码与每页条数在这里夹紧，不让前端传 {@code size=100000} 把服务打爆。
     */
    @Override
    public PageResult<TaskRun> pageRuns(String taskId, String status, Integer page, Integer size) {
        return repository.pageRuns(taskId, status,
                PageQueryUtil.normalizePage(page), PageQueryUtil.normalizeSize(size));
    }

    // ==================================================================
    // 编辑 / 删除（FR-D1 的配置维护）
    // ==================================================================

    /**
     * 编辑任务配置。只允许改 {@code name} / {@code maxPages} / {@code remark}。
     *
     * <h3>为什么运行中不允许编辑</h3>
     * 一次运行的参数在 {@code launch()} 时就固化成 {@code CollectCommand} 了
     * （见 {@link TaskRunner}）。运行中改 {@code maxPages} 不会影响这次运行，
     * 但界面上「最大页数」已经变了 —— 用户会以为改生效了。
     * 「改了不生效」比「不让改」更让人困惑，所以这里选择直接拒绝。
     *
     * <h3>为什么不允许改 targetType / targetId</h3>
     * 它们是游标的归属键（{@code collect_cursors} 的主键是
     * {@code (targetType, targetId)}）。改了目标等于换了一本账，
     * 而任务名、历史运行记录都还指着旧目标。想换目标就删了重建。
     */
    @Override
    public CollectTask update(String taskId, String name, Integer maxPages, String remark) {
        CollectTask task = requireTask(taskId);
        TaskStatus status = TaskStatus.of(task.getStatus());
        if (status.isActive()) {
            throw new BizException(ResultBean.code_warn,
                    "任务正在运行中（" + status.getLabel() + "），请先停止它再编辑");
        }

        Map<String, Object> sets = new LinkedHashMap<String, Object>();
        if (name != null && !name.trim().isEmpty()) {
            sets.put("name", name.trim());
        }
        if (maxPages != null) {
            sets.put("maxPages", normalizeMaxPages(maxPages));
        }
        if (remark != null) {
            sets.put("remark", remark);
        }
        if (sets.isEmpty()) {
            throw new BizException(ResultBean.code_warn,
                    "没有需要修改的字段（name / maxPages / remark 至少给一个）");
        }
        repository.updateTaskFields(taskId, sets);
        log.info("任务已编辑：{}，修改字段 {}", taskId, sets.keySet());
        return repository.findById(taskId);
    }

    /**
     * 删除任务（连带它的运行记录与日志）。
     *
     * <h3>🔴 运行中禁止删除</h3>
     * 采集线程正在跑，它的 {@code TaskHandle} 还挂在 {@link TaskRegistry} 里。
     * 如果此时删掉任务文档：
     * <ul>
     *   <li>线程收尾时 {@code ProgressReporter} 更新不到 run（已删），只会打一行 warn；</li>
     *   <li>但采集<b>会继续跑下去</b>，继续推游标、继续写图书 ——
     *       用户以为「删了就不采了」，实际还在跑，这是最危险的一类误解。</li>
     * </ul>
     * 所以必须先「取消」，等线程退出后再删。
     *
     * <h3>⚠️ 不删游标</h3>
     * 游标是<b>目标</b>的属性，不是任务的属性（见 {@code CollectCursor} 类注释）。
     * 删任务保留游标，意味着「删掉任务、重建一个同名任务」会从原来的位置继续采 ——
     * 这通常正是用户想要的。想从头采请去「断点续传」页重置游标。
     *
     * @return 级联删除的统计，供前端给出准确提示
     */
    @Override
    public Map<String, Object> delete(String taskId) {
        CollectTask task = requireTask(taskId);
        TaskStatus status = TaskStatus.of(task.getStatus());
        if (status.isActive()) {
            throw new BizException(ResultBean.code_warn,
                    "任务正在运行中（" + status.getLabel() + "），请先「取消」它，等它停止后再删除");
        }

        // 顺序：日志 → 运行记录 → 任务。
        // 反过来也能跑，但先删子记录能保证「任何时刻中断都不会留下指向已删任务的孤儿」
        long logs = repository.deleteLogsByTaskId(taskId);
        long runs = repository.deleteRunsByTaskId(taskId);
        repository.deleteById(taskId);

        log.info("任务已删除：{}（{}），级联删除运行记录 {} 条、日志 {} 条",
                taskId, task.getName(), runs, logs);

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("taskId", taskId);
        data.put("runsDeleted", runs);
        data.put("logsDeleted", logs);
        return data;
    }

    // ==================================================================
    // 四个动作
    // ==================================================================

    /**
     * 启动任务：{@code PENDING} / {@code INTERRUPTED} → {@code RUNNING}。
     *
     * @return 新建的运行记录（含 runId，前端拿它去轮询进度）
     */
    @Override
    public TaskRun start(String taskId) {
        CollectTask task = requireTask(taskId);
        TaskStatus status = TaskStatus.of(task.getStatus());
        if (status.isActive()) {
            throw new BizException(ResultBean.code_warn,
                    "任务正在运行中（" + status.getLabel() + "），无需重复启动");
        }
        if (!status.isResumable()) {
            throw new BizException(ResultBean.code_warn,
                    "任务已结束（" + status.getLabel() + "），请用「重试」重新运行");
        }
        return launch(task, status);
    }

    /**
     * 重试：终态（{@code SUCCESS} / {@code FAILED} / {@code CANCELED}）→ 再跑一次。
     *
     * <p>从<b>当前游标</b>续传，不是从头。要重头采请先重置游标（FR-E2）。
     */
    @Override
    public TaskRun retry(String taskId) {
        CollectTask task = requireTask(taskId);
        TaskStatus status = TaskStatus.of(task.getStatus());
        if (status.isActive()) {
            throw new BizException(ResultBean.code_warn,
                    "任务正在运行中（" + status.getLabel() + "），请先停止它再重试");
        }
        if (!status.isTerminal()) {
            throw new BizException(ResultBean.code_warn,
                    "任务还没结束（" + status.getLabel() + "），请用「启动」");
        }
        return launch(task, status);
    }

    /**
     * 暂停：{@code RUNNING} → {@code PAUSED}。
     *
     * <p>「暂停」是<b>请求</b>而非立即生效：采集线程会在<b>当前页跑完</b>之后
     * 停住，不再发新请求。所以调用方看到的状态立刻变 PAUSED，
     * 但 {@code api_requests} 可能还会多出一条 —— 这是正常的。
     */
    @Override
    public void pause(String taskId) {
        CollectTask task = requireTask(taskId);
        TaskStatus status = TaskStatus.of(task.getStatus());
        if (status != TaskStatus.RUNNING) {
            throw new BizException(ResultBean.code_warn,
                    "只有「运行中」的任务才能暂停，当前状态是「" + status.getLabel() + "」");
        }
        TaskHandle handle = registry.find(task.getTargetType(), task.getTargetId());
        if (handle == null || !taskId.equals(handle.taskId())) {
            // 状态说在跑，内存里却没有 —— 说明是上次进程留下的假死记录
            throw new BizException(ResultBean.code_warn,
                    "任务没有真正在运行（可能是上次进程遗留的状态），请用「取消」清理后重试");
        }
        if (!repository.casTaskStatus(taskId, TaskStatus.RUNNING, TaskStatus.PAUSED)) {
            throw new BizException(ResultBean.code_warn,
                    "任务状态已被其他操作改变，暂停未生效，请刷新后重试");
        }
        handle.pause();
        progress.info(handle.runId(), taskId, "已请求暂停，当前页跑完后会停住（游标已保留）");
        log.info("任务已暂停：{}（run={}）", taskId, handle.runId());
    }

    /**
     * 恢复。
     * <ul>
     *   <li>{@code PAUSED} 且采集线程还在 → <b>原地唤醒</b>，同一 run 继续，一页都不重采</li>
     *   <li>{@code PAUSED} 但线程已不在（异常情况）→ 新开一次运行</li>
     *   <li>{@code INTERRUPTED} → 新开一次运行，从游标续传</li>
     * </ul>
     */
    @Override
    public TaskRun resume(String taskId) {
        CollectTask task = requireTask(taskId);
        TaskStatus status = TaskStatus.of(task.getStatus());

        if (status == TaskStatus.PAUSED) {
            TaskHandle handle = registry.find(task.getTargetType(), task.getTargetId());
            if (handle != null && taskId.equals(handle.taskId())) {
                if (!repository.casTaskStatus(taskId, TaskStatus.PAUSED, TaskStatus.RUNNING)) {
                    throw new BizException(ResultBean.code_warn,
                            "任务状态已被其他操作改变，恢复未生效，请刷新后重试");
                }
                handle.resume();
                progress.info(handle.runId(), taskId, "任务已恢复，从游标继续（原地唤醒，未重采）");
                log.info("任务已原地恢复：{}（run={}）", taskId, handle.runId());
                return repository.findRunById(handle.runId());
            }
            // 兜底：PAUSED 却没有线程。正常情况下不可能（暂停的线程一定阻塞着）。
            // 当成中断处理，新开一次运行 —— 反正游标还在，重采一页也是幂等的。
            log.warn("任务 {} 状态为 PAUSED 但内存中没有对应线程，按中断处理，新开一次运行", taskId);
        }

        if (status.isResumable() || status.isTerminal()) {
            return launch(task, status);
        }
        throw new BizException(ResultBean.code_warn,
                "当前状态「" + status.getLabel() + "」不支持恢复");
    }

    /**
     * 取消。
     *
     * <p>分两种情况：
     * <ul>
     *   <li>有采集线程 → 置 CANCELED 后 {@code handle.cancel()}，线程会被唤醒并抛取消异常；
     *       run 的终态由 {@link TaskRunner} 写（它是所有者）</li>
     *   <li>没有线程（假死记录）→ 置 CANCELED 并顺手把 run 收尾，否则那条 run 会永远停在 RUNNING</li>
     * </ul>
     */
    @Override
    public void cancel(String taskId) {
        CollectTask task = requireTask(taskId);
        TaskStatus status = TaskStatus.of(task.getStatus());
        if (status.isTerminal()) {
            throw new BizException(ResultBean.code_warn,
                    "任务已经结束了（" + status.getLabel() + "），无需取消");
        }

        if (!status.isActive()) {
            // PENDING / INTERRUPTED：没有线程在跑，直接置终态
            if (!repository.casTaskStatus(taskId, status, TaskStatus.CANCELED)) {
                throw new BizException(ResultBean.code_warn, "任务状态已变化，取消失败，请刷新后重试");
            }
            progress.info(task.getLastRunId(), taskId, "任务已取消（当时未在运行）");
            log.info("任务已取消（无运行线程）：{}", taskId);
            return;
        }

        TaskHandle handle = registry.find(task.getTargetType(), task.getTargetId());
        if (handle != null && taskId.equals(handle.taskId())) {
            if (!repository.casTaskStatus(taskId, status, TaskStatus.CANCELED)) {
                throw new BizException(ResultBean.code_warn, "任务状态已变化，取消失败，请刷新后重试");
            }
            // 唤醒并让 CollectLoop 在下一个检查点抛取消异常
            handle.cancel();
            log.info("任务已取消：{}（run={}），等待采集线程收尾", taskId, handle.runId());
            return;
        }

        // 假死：状态说在跑，内存里没有线程。必须自己收尾，否则 run 永远停在 RUNNING
        if (!repository.casTaskStatus(taskId, status, TaskStatus.CANCELED)) {
            throw new BizException(ResultBean.code_warn, "任务状态已变化，取消失败，请刷新后重试");
        }
        TaskRun latest = repository.findLatestRun(taskId);
        if (latest != null && TaskStatus.of(latest.getStatus()).isActive()) {
            progress.finish(latest.getId(), TaskStatus.CANCELED, "任务已被取消（进程重启后清理）");
        }
        log.warn("任务已取消（假死记录，无运行线程）：{}", taskId);
    }

    // ==================================================================
    // 内部：真正把一次运行拉起来
    // ==================================================================

    /**
     * 拉起一次运行。步骤：全局闸门 → 建 run → 抢占目标 → CAS 状态 → 提交线程池。
     *
     * <p>任何一步失败都要<b>回滚前面已做的事</b>，否则会留下
     * 「状态是 RUNNING 但没人在跑」的假死记录 —— 那正是最难排查的一类 bug。
     */
    private TaskRun launch(CollectTask task, TaskStatus fromStatus) {
        String taskId = task.getId();
        String targetType = task.getTargetType();
        String targetId = task.getTargetId();
        String label = task.getTargetName() == null ? targetId : task.getTargetName();

        // ---- 1. 全局并发闸门（对齐 2.5：同时最多 1 个采集任务）----
        int max = Math.max(1, properties.getMaxConcurrentRuns());
        if (registry.activeCount() >= max) {
            String who = "";
            List<TaskHandle> active = registry.listActive();
            if (!active.isEmpty()) {
                who = "（正在跑：「" + active.get(0).targetLabel() + "」）";
            }
            throw new BizException(ResultBean.code_warn,
                    "已有 " + registry.activeCount() + " 个采集任务在运行，最多允许 " + max
                            + " 个" + who + "。请先停止它");
        }

        // ---- 2. 建运行记录（先建，接口就能立刻把 runId 返回给前端）----
        TargetType type = TargetType.of(targetType);
        int startCursor = cursorStore.loadCursor(type, targetId);
        TaskRun run = TaskRun.builder()
                .taskId(taskId)
                .taskName(task.getName())
                .targetType(targetType)
                .targetId(targetId)
                .targetName(task.getTargetName())
                .startedAt(new Date())
                .status(TaskStatus.RUNNING.name())
                .pagesDone(0)
                .booksSaved(0L)
                .currentCursor(startCursor)
                .lastProgressAt(new Date())
                .build();
        run = repository.saveRun(run);

        // ---- 3. 抢占目标采集权（FR-D6）----
        TaskHandle handle;
        try {
            handle = registry.acquire(taskId, run.getId(), targetType, targetId, label);
        } catch (RuntimeException e) {
            failRun(run, e.getMessage());
            throw e;
        }

        // ---- 4. CAS 状态 → RUNNING ----
        if (!repository.casTaskStatus(taskId, fromStatus, TaskStatus.RUNNING)) {
            registry.release(handle.key());
            failRun(run, "任务状态在启动过程中被改变，启动已取消");
            throw new BizException(ResultBean.code_warn,
                    "任务状态在启动过程中被改变，启动已取消，请刷新后重试");
        }
        repository.markTaskStarted(taskId, run.getId());

        // ---- 5. 提交线程池 ----
        try {
            runner.run(task, run, handle);
        } catch (TaskRejectedException e) {
            // 队列满。必须回滚，否则状态是 RUNNING 但没人在跑
            registry.release(handle.key());
            repository.setTaskStatus(taskId, TaskStatus.FAILED);
            failRun(run, "采集线程池已满，任务未能启动");
            throw new BizException(ResultBean.code_warn,
                    "采集线程池繁忙，任务未能启动，请稍后重试");
        } catch (RuntimeException e) {
            registry.release(handle.key());
            repository.setTaskStatus(taskId, TaskStatus.FAILED);
            failRun(run, "任务提交失败：" + e.getMessage());
            throw e;
        }

        log.info("任务已启动：{}（run={}，目标 {}({})，起始游标 {}）",
                taskId, run.getId(), label, targetId, startCursor);
        return run;
    }

    /** 把一条没能跑起来的 run 收尾，避免留下永远 RUNNING 的记录 */
    private void failRun(TaskRun run, String reason) {
        try {
            run.setStatus(TaskStatus.FAILED.name());
            run.setFinishedAt(new Date());
            run.setErrorMsg(reason);
            run.setLastProgressAt(new Date());
            repository.saveRun(run);
        } catch (Exception e) {
            log.error("收尾失败的运行记录时又出错：run={}", run.getId(), e);
        }
    }

    // ==================================================================

    private CollectTask requireTask(String taskId) {
        if (taskId == null || taskId.trim().isEmpty()) {
            throw new BizException(ResultBean.code_warn, "taskId 不能为空");
        }
        CollectTask task = repository.findById(taskId);
        if (task == null) {
            throw BizException.notFound("任务不存在：" + taskId);
        }
        return task;
    }

    /** {@code null} / 负数归一成 0，表示「不限页数」 */
    private Integer normalizeMaxPages(Integer maxPages) {
        if (maxPages == null || maxPages < 0) {
            return 0;
        }
        return maxPages;
    }

    private String describeMaxPages(Integer maxPages) {
        return (maxPages == null || maxPages <= 0) ? "不限" : String.valueOf(maxPages);
    }
}
