package com.bookcollector.task;

import com.bookcollector.common.enums.TaskStatus;
import com.bookcollector.task.entity.CollectTask;
import com.bookcollector.task.entity.TaskLog;
import com.bookcollector.task.entity.TaskRun;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 任务 / 运行 / 日志的 Mongo 访问。
 *
 * <h3>🔴 为什么状态流转用 CAS（compare-and-set）而不是直接 {@code save()}</h3>
 * 状态是<b>多线程共享</b>的：HTTP 线程可能在处理「暂停」的同时，
 * 采集线程正好跑完并写终态。如果两边都读-改-写，后写的会覆盖先写的，
 * 出现「暂停成功但任务其实已经跑完了」这种脏状态。
 *
 * <p>{@link #casTaskStatus} 把「当前状态必须是 from」作为查询条件的一部分，
 * 交给 MongoDB 原子执行：匹配不到就说明状态已被别人改过，返回 {@code false}，
 * 调用方据此放弃本次动作。这是最轻量的乐观锁，不需要版本号字段。
 *
 * <h3>⚠️ 但 TaskRunner 写终态时不用 CAS</h3>
 * 采集线程是这次运行的<b>所有者</b>，它写的终态是权威结果，必须能覆盖
 * 「运行期间用户点了暂停」这类中间状态 —— 否则任务会永远卡在 PAUSED。
 * 所以有 {@link #setTaskStatus}（无条件）与 {@link #casTaskStatus}（有条件）两个方法，
 * 用哪个取决于「你是不是这次运行的所有者」。
 */
@Repository
public class TaskRepository {

    private final MongoTemplate mongoTemplate;

    public TaskRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // ==================================================================
    // collect_tasks
    // ==================================================================

    public CollectTask save(CollectTask task) {
        return mongoTemplate.save(task);
    }

    public CollectTask findById(String id) {
        return mongoTemplate.findById(id, CollectTask.class);
    }

    public List<CollectTask> findAll() {
        Query q = new Query();
        q.with(Sort.by(Sort.Direction.DESC, "createdAt"));
        return mongoTemplate.find(q, CollectTask.class);
    }

    public void deleteById(String id) {
        mongoTemplate.remove(Query.query(Criteria.where("_id").is(id)), CollectTask.class);
    }

    /**
     * 按目标统计任务数。
     *
     * <p>用途：删除字典项前检查「还有没有任务引用这个目标」
     * （见 {@code TaxonomyService#delete}）。因为 {@code uk_target} 是唯一索引，
     * 这个值只可能是 0 或 1 —— 但返回值类型仍用 long，语义是「引用数」，
     * 将来万一放开「一个目标多个任务」也不用改签名。
     */
    public long countByTarget(String targetType, String targetId) {
        if (targetType == null || targetId == null) {
            return 0L;
        }
        return mongoTemplate.count(
                Query.query(Criteria.where("targetType").is(targetType).and("targetId").is(targetId)),
                CollectTask.class);
    }

    /**
     * 局部更新任务的配置字段（编辑任务用）。
     *
     * <p>只允许改 {@code name} / {@code maxPages} / {@code remark} 这类「配置」。
     * {@code targetType} / {@code targetId} 不能改 —— 它们是游标的归属键；
     * {@code status} / {@code runCount} / {@code lastRunId} 更不能改，那是运行时账本。
     * 白名单由调用方（TaskService）组装，这里只负责下发。
     */
    public boolean updateTaskFields(String taskId, Map<String, Object> sets) {
        if (taskId == null || taskId.trim().isEmpty() || sets == null || sets.isEmpty()) {
            return false;
        }
        Update u = new Update();
        for (Map.Entry<String, Object> entry : sets.entrySet()) {
            u.set(entry.getKey(), entry.getValue());
        }
        u.set("updatedAt", new Date());
        return mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(taskId)), u, CollectTask.class)
                .getMatchedCount() == 1L;
    }

    /**
     * 条件状态流转（乐观锁）。
     *
     * @return true = 本次流转生效；false = 当前状态不是 {@code from}，被别人改过了
     */
    public boolean casTaskStatus(String taskId, TaskStatus from, TaskStatus to) {
        Query q = Query.query(Criteria.where("_id").is(taskId)
                .and("status").is(from.name()));
        Update u = new Update().set("status", to.name()).set("updatedAt", new Date());
        return mongoTemplate.updateFirst(q, u, CollectTask.class).getMatchedCount() == 1L;
    }

    /** 无条件写状态。只有「这次运行的所有者」（TaskRunner）才该用它 */
    public void setTaskStatus(String taskId, TaskStatus status) {
        Update u = new Update().set("status", status.name()).set("updatedAt", new Date());
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(taskId)), u,
                CollectTask.class);
    }

    /** 记录「本次运行」：更新 lastRunId 并把 runCount 加一 */
    public void markTaskStarted(String taskId, String runId) {
        Update u = new Update()
                .set("lastRunId", runId)
                .inc("runCount", 1)
                .set("updatedAt", new Date());
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(taskId)), u,
                CollectTask.class);
    }

    // ==================================================================
    // collect_task_runs
    // ==================================================================

    public TaskRun saveRun(TaskRun run) {
        return mongoTemplate.save(run);
    }

    public TaskRun findRunById(String runId) {
        return mongoTemplate.findById(runId, TaskRun.class);
    }

    public List<TaskRun> findRunsByTaskId(String taskId, int limit) {
        Query q = Query.query(Criteria.where("taskId").is(taskId));
        q.with(Sort.by(Sort.Direction.DESC, "startedAt"));
        q.limit(limit);
        return mongoTemplate.find(q, TaskRun.class);
    }

    /** 某任务最近一次运行 */
    public TaskRun findLatestRun(String taskId) {
        List<TaskRun> runs = findRunsByTaskId(taskId, 1);
        return runs.isEmpty() ? null : runs.get(0);
    }

    /** 某个目标最近一次运行（不区分任务） */
    public TaskRun findLatestRunByTarget(String targetType, String targetId) {
        Query q = Query.query(Criteria.where("targetType").is(targetType)
                .and("targetId").is(targetId));
        q.with(Sort.by(Sort.Direction.DESC, "startedAt"));
        q.limit(1);
        List<TaskRun> runs = mongoTemplate.find(q, TaskRun.class);
        return runs.isEmpty() ? null : runs.get(0);
    }

    /**
     * 找出所有「活跃」的运行记录（RUNNING / PAUSED）。
     * 供 {@link InterruptedTaskDetector} 在启动时清理。
     */
    public List<TaskRun> findActiveRuns() {
        Query q = Query.query(Criteria.where("status")
                .in(Arrays.asList(TaskStatus.RUNNING.name(), TaskStatus.PAUSED.name())));
        return mongoTemplate.find(q, TaskRun.class);
    }

    public List<CollectTask> findActiveTasks() {
        Query q = Query.query(Criteria.where("status")
                .in(Arrays.asList(TaskStatus.RUNNING.name(), TaskStatus.PAUSED.name())));
        return mongoTemplate.find(q, CollectTask.class);
    }

    // ==================================================================
    // collect_task_logs
    // ==================================================================

    public List<TaskLog> findLogsByRunId(String runId, int limit) {
        Query q = Query.query(Criteria.where("runId").is(runId));
        q.with(Sort.by(Sort.Direction.ASC, "createdAt"));
        q.limit(limit);
        return mongoTemplate.find(q, TaskLog.class);
    }

    public List<TaskLog> findLogsByTaskId(String taskId, int limit) {
        Query q = Query.query(Criteria.where("taskId").is(taskId));
        q.with(Sort.by(Sort.Direction.DESC, "createdAt"));
        q.limit(limit);
        return mongoTemplate.find(q, TaskLog.class);
    }

    // ==================================================================
    // 运行记录：跨任务分页查询（FR-D5）
    // ==================================================================

    /**
     * 运行记录分页。{@code taskId} / {@code status} 都为 null 时就是「全部运行记录」。
     *
     * <p>先 count 再 find 两次往返。第一期数据量（一个任务几十次运行）完全够用；
     * 将来若上到十万级，再换游标分页。
     */
    public List<TaskRun> findRunsPage(String taskId, String status, int page, int size) {
        Query q = buildRunQuery(taskId, status);
        q.with(Sort.by(Sort.Direction.DESC, "startedAt").and(Sort.by(Sort.Direction.ASC, "_id")));
        q.with(org.springframework.data.domain.PageRequest.of(page - 1, size));
        return mongoTemplate.find(q, TaskRun.class);
    }

    public long countRuns(String taskId, String status) {
        return mongoTemplate.count(buildRunQuery(taskId, status), TaskRun.class);
    }

    /** 多个条件用 andOperator 组合，避免同字段叠加时被覆盖 */
    private Query buildRunQuery(String taskId, String status) {
        List<Criteria> conds = new java.util.ArrayList<Criteria>();
        if (taskId != null && !taskId.trim().isEmpty()) {
            conds.add(Criteria.where("taskId").is(taskId.trim()));
        }
        if (status != null && !status.trim().isEmpty()) {
            conds.add(Criteria.where("status").is(status.trim().toUpperCase()));
        }
        Query q = new Query();
        if (conds.size() == 1) {
            q.addCriteria(conds.get(0));
        } else if (conds.size() > 1) {
            q.addCriteria(new Criteria().andOperator(conds.toArray(new Criteria[0])));
        }
        return q;
    }

    // ==================================================================
    // 清理（删除任务时的级联）
    // ==================================================================

    /**
     * 删除某任务的全部运行记录。
     *
     * <h3>为什么删除任务要级联删运行记录</h3>
     * 「运行记录」列表页是按任务维度组织的（{@code /api/runs?taskId=x}），
     * 任务没了之后这些运行记录<b>没有任何界面入口能到达</b>。
     * 留着它们不是「保留历史」，只是「制造孤儿数据」——
     * S3 阶段就踩过一次这个坑（{@code collect_task_logs} 攒了 19 条孤儿日志，
     * 界面查不到、也永远不会被清理）。
     *
     * <p>如果将来真的需要「任务删了但流水留着」，正确做法是加
     * {@code deleted} 软删标记，而不是靠「不删」来保留 —— 那是两回事。
     */
    public long deleteRunsByTaskId(String taskId) {
        if (taskId == null || taskId.trim().isEmpty()) {
            return 0L;
        }
        return mongoTemplate.remove(Query.query(Criteria.where("taskId").is(taskId.trim())),
                TaskRun.class).getDeletedCount();
    }

    /** 删除某任务的全部日志 */
    public long deleteLogsByTaskId(String taskId) {
        if (taskId == null || taskId.trim().isEmpty()) {
            return 0L;
        }
        return mongoTemplate.remove(Query.query(Criteria.where("taskId").is(taskId.trim())),
                TaskLog.class).getDeletedCount();
    }

    /** 删除某次运行的全部日志 */
    public long deleteLogsByRunId(String runId) {
        if (runId == null || runId.trim().isEmpty()) {
            return 0L;
        }
        return mongoTemplate.remove(Query.query(Criteria.where("runId").is(runId.trim())),
                TaskLog.class).getDeletedCount();
    }
}
