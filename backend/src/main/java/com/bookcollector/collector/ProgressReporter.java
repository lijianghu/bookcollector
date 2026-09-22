package com.bookcollector.collector;

import com.bookcollector.common.enums.TaskStatus;
import com.bookcollector.task.entity.TaskLog;
import com.bookcollector.task.entity.TaskRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 把采集进度写进 {@code collect_task_runs}，把关键事件写进 {@code collect_task_logs}。
 *
 * <h3>写放大控制</h3>
 * 进度<b>每页更新一次</b>，不是每本书一次。一页 20 本，每本写一次就是 20 倍写放大，
 * 而进度精度并没有实际收益（用户看到「第 37 页」和「第 37 页+3 本」没区别）。
 *
 * <h3>{@code lastProgressAt} 的作用</h3>
 * 这是判断任务<b>是否假死</b>的唯一可靠依据。页面轮询时如果
 * {@code now - lastProgressAt} 远超「一页的正常耗时」，说明卡住了，
 * 而不是「在慢慢跑」—— 光看 {@code pagesDone} 不动是分不清这两者的。
 *
 * <h3>容错</h3>
 * 所有方法在 {@code runId} 为 null 或对应 run 不存在时<b>静默降级</b>
 * （只打日志，不抛异常）。理由：进度上报失败不该搞挂采集本身 ——
 * 书已经落库了，丢一条进度记录是小事。
 */
@Component
public class ProgressReporter {

    private static final Logger log = LoggerFactory.getLogger(ProgressReporter.class);

    private final MongoTemplate mongoTemplate;

    public ProgressReporter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * 每页结束后刷新进度。
     *
     * @param pagesDone   已完成的页数
     * @param booksSaved  累计写入的图书数
     * @param cursor      当前游标值
     * @param totalCount  接口报告的总书数，可为 null
     */
    public void onPage(String runId, int pagesDone, long booksSaved, int cursor, Integer totalCount) {
        if (runId == null) {
            return;
        }
        Update update = new Update()
                .set("pagesDone", pagesDone)
                .set("booksSaved", booksSaved)
                .set("currentCursor", cursor)
                .set("lastProgressAt", new Date());
        if (totalCount != null) {
            update.set("totalCount", totalCount);
        }
        apply(runId, update, "onPage");
    }

    /**
     * 收尾：写入终态。
     *
     * @param status   终态（SUCCESS / FAILED / CANCELED / INTERRUPTED）
     * @param errorMsg 失败原因，成功时为 null
     */
    public void finish(String runId, TaskStatus status, String errorMsg) {
        if (runId == null) {
            return;
        }
        Update update = new Update()
                .set("status", status.name())
                .set("finishedAt", new Date())
                .set("lastProgressAt", new Date());
        if (errorMsg != null) {
            update.set("errorMsg", errorMsg);
        }
        apply(runId, update, "finish");
    }

    /**
     * 写一条运行日志。
     *
     * <p>{@code level} 用 {@link TaskLog} 上的常量，不要直接写字符串。
     */
    public void log(String runId, String taskId, String level, String message) {
        try {
            mongoTemplate.insert(TaskLog.builder()
                    .runId(runId)
                    .taskId(taskId)
                    .level(level)
                    .message(message)
                    .build());
        } catch (Exception e) {
            // 日志写不进去也不能影响采集
            log.warn("写运行日志失败（runId={}）：{}", runId, e.getMessage());
        }
    }

    public void info(String runId, String taskId, String message) {
        log(runId, taskId, TaskLog.LEVEL_INFO, message);
    }

    public void warn(String runId, String taskId, String message) {
        log(runId, taskId, TaskLog.LEVEL_WARN, message);
    }

    public void error(String runId, String taskId, String message) {
        log(runId, taskId, TaskLog.LEVEL_ERROR, message);
    }

    /** 统一执行 Update，并把「没匹配到 run」这件事暴露到日志里 */
    private void apply(String runId, Update update, String action) {
        try {
            com.mongodb.client.result.UpdateResult result = mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(runId)), update, TaskRun.class);
            if (result.getMatchedCount() == 0L) {
                log.warn("进度上报未匹配到 run（runId={}, action={}）—— 该 run 可能已被删除",
                        runId, action);
            }
        } catch (Exception e) {
            log.warn("进度上报失败（runId={}, action={}）：{}", runId, action, e.getMessage());
        }
    }
}
