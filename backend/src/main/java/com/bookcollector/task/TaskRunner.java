package com.bookcollector.task;

import com.bookcollector.collector.CollectCommand;
import com.bookcollector.collector.CollectLoop;
import com.bookcollector.collector.CollectResult;
import com.bookcollector.collector.ProgressReporter;
import com.bookcollector.common.TraceIdFilter;
import com.bookcollector.common.enums.TargetType;
import com.bookcollector.common.enums.TaskStatus;
import com.bookcollector.config.AsyncConfig;
import com.bookcollector.task.entity.CollectTask;
import com.bookcollector.task.entity.TaskRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 采集任务的执行体 —— 跑在采集线程池里，把 {@link CollectLoop} 包一层编排逻辑。
 *
 * <h3>职责边界</h3>
 * <ul>
 *   <li>{@link CollectLoop}：只负责「采」，不碰任务状态（S2 的设计，便于独立测试）</li>
 *   <li>{@link TaskRunner}：负责「把采集结果翻译成任务终态」+ 释放采集权 + MDC 管理</li>
 *   <li>{@link TaskService}：负责「启停动作与状态校验」，是 HTTP 层的入口</li>
 * </ul>
 *
 * <h3>🔴 终态是无条件写入的（不走 CAS）</h3>
 * 采集线程是这次运行的<b>所有者</b>，它写的终态是权威结果。
 * 运行期间用户可能点过暂停（状态被改成 PAUSED），如果这里也走 CAS，
 * 就会因为「当前状态不是 RUNNING」而写不进去 —— 任务永远卡在 PAUSED。
 * 所以终态用 {@link TaskRepository#setTaskStatus}（无条件）覆盖。
 *
 * <h3>MDC 透传（B4）</h3>
 * {@code @Async} 线程<b>不继承</b>调用方的 MDC。这里在任务开始时手动
 * {@code MDC.put("threadId", ...)}，结束时 {@code remove} —— 必须 remove，
 * 否则线程池复用会让下一个任务带着上一个任务的 traceId（串味）。
 *
 * <h3>异常兜底</h3>
 * {@link CollectLoop#run} 承诺不抛异常，但 TaskRunner 自己可能出错
 * （比如写库失败）。这里用 {@code catch Throwable} 兜住，
 * 保证「采集权一定被释放」—— 否则那个目标会永远无法再次启动。
 */
@Component
public class TaskRunner {

    private static final Logger log = LoggerFactory.getLogger(TaskRunner.class);

    private final CollectLoop collectLoop;
    private final TaskRepository repository;
    private final TaskRegistry registry;
    private final ProgressReporter progress;

    public TaskRunner(CollectLoop collectLoop,
                      TaskRepository repository,
                      TaskRegistry registry,
                      ProgressReporter progress) {
        this.collectLoop = collectLoop;
        this.repository = repository;
        this.registry = registry;
        this.progress = progress;
    }

    /**
     * 执行一次采集。由 {@link TaskService} 提交到采集线程池。
     *
     * <p>注意是 {@code public} 且通过 Spring 代理调用才生效 ——
     * {@link TaskService} 持有的是 {@code TaskRunner} 的代理 bean，
     * 所以 {@code @Async} 正常起作用（不是自调用）。
     *
     * @param task   任务快照（启动那一刻的配置）
     * @param run    已经建好的运行记录，状态为 RUNNING
     * @param handle 采集权句柄，同时作为暂停/取消信号
     */
    @Async(AsyncConfig.COLLECT_EXECUTOR)
    public void run(CollectTask task, TaskRun run, TaskHandle handle) {
        String traceId = newTraceId();
        MDC.put(TraceIdFilter.TRACE_ID_KEY, traceId);
        String runId = run.getId();
        String taskId = task.getId();
        try {
            log.info("[{}] 采集开始：task={} target={}({}) maxPages={} 起始游标={}",
                    traceId, taskId, task.getTargetName(), task.getTargetId(),
                    task.getMaxPages(), run.getCurrentCursor());
            progress.info(runId, taskId, "采集开始：目标「" + task.getTargetName()
                    + "」，最大页数 " + describeMaxPages(task.getMaxPages())
                    + "，起始游标 " + run.getCurrentCursor());

            CollectCommand command = CollectCommand.builder()
                    .runId(runId)
                    .taskId(taskId)
                    .targetType(TargetType.of(task.getTargetType()))
                    .targetId(task.getTargetId())
                    .targetName(task.getTargetName())
                    .maxPages(task.getMaxPages())
                    // 永远从游标续传。要「从头重采」应该先重置游标（FR-E2），
                    // 而不是让任务自己决定 —— 否则「启动」这个动作的含义就变得不可预测了。
                    .resetCursor(false)
                    .control(handle)
                    .build();

            CollectResult result = collectLoop.run(command);

            TaskStatus finalStatus = decideStatus(result);
            progress.finish(runId, finalStatus, result.getErrorMsg());
            // 无条件写终态，见类注释
            repository.setTaskStatus(taskId, finalStatus);

            String summary = buildSummary(result, finalStatus);
            log.info("[{}] 采集结束：task={} status={} {}", traceId, taskId, finalStatus, summary);
            progress.info(runId, taskId, summary);

            if (finalStatus == TaskStatus.SUCCESS && result.isHasMore()) {
                // 不是失败，但要让人知道「还没采到底」——否则会以为已经采完了
                progress.warn(runId, taskId,
                        "已达到页数上限，本目标还有更多数据。再次启动该任务即可从游标 "
                                + result.getCurrentCursor() + " 继续");
            }
        } catch (Throwable t) {
            // 兜底：CollectLoop 不抛异常，但这里自己可能出错（写库失败等）
            String msg = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "：" + t.getMessage());
            log.error("[{}] 采集执行体异常：task={}", traceId, taskId, t);
            try {
                progress.error(runId, taskId, "采集执行体异常：" + msg);
                progress.finish(runId, TaskStatus.FAILED, msg);
                repository.setTaskStatus(taskId, TaskStatus.FAILED);
            } catch (Exception nested) {
                log.error("[{}] 写失败终态时又出错：task={}", traceId, taskId, nested);
            }
        } finally {
            // ★ 无论如何都要释放采集权，否则该目标永远无法再启动
            registry.release(handle.key());
            MDC.remove(TraceIdFilter.TRACE_ID_KEY);
        }
    }

    /**
     * 把采集结果翻译成任务终态。
     *
     * <p>顺序不能反：<b>先看取消</b>。因为取消的实现方式是让 {@code CollectLoop}
     * 抛 {@code CollectCanceledException} 并置 {@code canceled=true}，
     * 此时 {@code errorMsg} 是 null，如果不先判取消就会误判成 SUCCESS。
     */
    private TaskStatus decideStatus(CollectResult result) {
        if (result.isCanceled()) {
            return TaskStatus.CANCELED;
        }
        if (result.isFailed()) {
            return TaskStatus.FAILED;
        }
        return TaskStatus.SUCCESS;
    }

    private String buildSummary(CollectResult result, TaskStatus status) {
        StringBuilder sb = new StringBuilder();
        sb.append(status.getLabel())
                .append("：采集 ").append(result.getPagesDone()).append(" 页")
                .append("，写入 ").append(result.getBooksSaved()).append(" 本")
                .append("，游标 ").append(result.getCurrentCursor())
                .append("，耗时 ").append(result.getCostMs()).append("ms");
        if (result.getTotalCount() != null) {
            sb.append("（该目标共 ").append(result.getTotalCount()).append(" 本）");
        }
        if (result.getErrorMsg() != null) {
            sb.append("；原因：").append(result.getErrorMsg());
        }
        return sb.toString();
    }

    private String describeMaxPages(Integer maxPages) {
        if (maxPages == null || maxPages <= 0) {
            return "不限";
        }
        return String.valueOf(maxPages);
    }

    private String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
