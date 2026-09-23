package com.bookcollector.task.service;

import com.bookcollector.common.PageResult;
import com.bookcollector.task.entity.CollectTask;
import com.bookcollector.task.entity.TaskLog;
import com.bookcollector.task.entity.TaskRun;

import java.util.List;
import java.util.Map;

/**
 * 任务编排的状态机 —— HTTP 层唯一的入口。
 *
 * <h3>状态机</h3>
 * <pre>
 *   PENDING ──start──► RUNNING ──pause──► PAUSED
 *                        │  ▲                │
 *                        │  └────resume──────┘
 *                        │
 *              ┌─────────┼─────────┐
 *              ▼         ▼         ▼
 *           SUCCESS   FAILED   CANCELED
 *
 *   任意活跃态 + JVM 重启 ──► INTERRUPTED ──start/resume──► RUNNING
 * </pre>
 *
 * <h3>四个动作的准入条件（互相不重叠，用户不会困惑）</h3>
 * <table border="1">
 *   <tr><th>动作</th><th>允许的当前状态</th><th>说明</th></tr>
 *   <tr><td>{@code start}</td><td>PENDING / INTERRUPTED</td><td>新开一次运行</td></tr>
 *   <tr><td>{@code pause}</td><td>RUNNING</td><td>阻塞式暂停，可原地恢复</td></tr>
 *   <tr><td>{@code resume}</td><td>PAUSED / INTERRUPTED / PENDING</td><td>PAUSED 唤醒线程；其余新开一次运行</td></tr>
 *   <tr><td>{@code cancel}</td><td>非终态</td><td>置 CANCELED；有线程就一并唤醒</td></tr>
 *   <tr><td>{@code retry}</td><td>终态</td><td>失败/取消/完成后再跑一次（从游标续传）</td></tr>
 * </table>
 *
 * <h3>🔴 状态写入的分工（重要）</h3>
 * <ul>
 *   <li><b>本接口的实现</b>写中间态（RUNNING / PAUSED / CANCELED），一律走
 *       {@link com.bookcollector.task.repository.TaskRepository#casTaskStatus}（乐观锁）
 *       —— 因为 HTTP 线程可能并发操作同一任务。</li>
 *   <li><b>{@link com.bookcollector.task.TaskRunner}</b> 写终态，无条件覆盖 —— 它是这次运行的所有者，
 *       终态是权威结果，必须能盖掉运行期间用户点的暂停。</li>
 * </ul>
 * 这条分工不能乱：如果这里也写终态，会和 TaskRunner 抢；
 * 如果 TaskRunner 也走 CAS，任务会卡在 PAUSED 永远结束不了。
 *
 * <h3>为什么每次运行都新建一条 TaskRun</h3>
 * 任务定义（{@link CollectTask}）是静态配置，运行记录是流水。
 * 「上一次跑到第 37 页失败了」这件事本身有价值，不能被下次运行覆盖掉。
 *
 * @see com.bookcollector.task.service.impl.TaskServiceImpl
 */
public interface TaskService {

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
    CollectTask create(String name, String targetType, String targetId,
                       String targetName, Integer maxPages, String remark);

    CollectTask get(String taskId);

    List<CollectTask> list();

    List<TaskRun> listRuns(String taskId, Integer limit);

    TaskRun getRun(String runId);

    List<TaskLog> listLogs(String runId, Integer limit);

    /**
     * 运行记录分页（FR-D5，跨任务）。
     *
     * <p>{@code taskId} / {@code status} 都为 null 时就是「全部运行记录」。
     * 页码与每页条数在这里夹紧，不让前端传 {@code size=100000} 把服务打爆。
     */
    PageResult<TaskRun> pageRuns(String taskId, String status, Integer page, Integer size);

    // ==================================================================
    // 编辑 / 删除（FR-D1 的配置维护）
    // ==================================================================

    /**
     * 编辑任务配置。只允许改 {@code name} / {@code maxPages} / {@code remark}。
     *
     * <h3>为什么运行中不允许编辑</h3>
     * 一次运行的参数在 {@code launch()} 时就固化成 {@code CollectCommand} 了
     * （见 {@link com.bookcollector.task.TaskRunner}）。运行中改 {@code maxPages} 不会影响这次运行，
     * 但界面上「最大页数」已经变了 —— 用户会以为改生效了。
     * 「改了不生效」比「不让改」更让人困惑，所以这里选择直接拒绝。
     *
     * <h3>为什么不允许改 targetType / targetId</h3>
     * 它们是游标的归属键（{@code collect_cursors} 的主键是
     * {@code (targetType, targetId)}）。改了目标等于换了一本账，
     * 而任务名、历史运行记录都还指着旧目标。想换目标就删了重建。
     */
    CollectTask update(String taskId, String name, Integer maxPages, String remark);

    /**
     * 删除任务（连带它的运行记录与日志）。
     *
     * <h3>🔴 运行中禁止删除</h3>
     * 采集线程正在跑，它的 {@code TaskHandle} 还挂在
     * {@link com.bookcollector.task.TaskRegistry} 里。
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
    Map<String, Object> delete(String taskId);

    // ==================================================================
    // 四个动作
    // ==================================================================

    /**
     * 启动任务：{@code PENDING} / {@code INTERRUPTED} → {@code RUNNING}。
     *
     * @return 新建的运行记录（含 runId，前端拿它去轮询进度）
     */
    TaskRun start(String taskId);

    /**
     * 重试：终态（{@code SUCCESS} / {@code FAILED} / {@code CANCELED}）→ 再跑一次。
     *
     * <p>从<b>当前游标</b>续传，不是从头。要重头采请先重置游标（FR-E2）。
     */
    TaskRun retry(String taskId);

    /**
     * 暂停：{@code RUNNING} → {@code PAUSED}。
     *
     * <p>「暂停」是<b>请求</b>而非立即生效：采集线程会在<b>当前页跑完</b>之后
     * 停住，不再发新请求。所以调用方看到的状态立刻变 PAUSED，
     * 但 {@code api_requests} 可能还会多出一条 —— 这是正常的。
     */
    void pause(String taskId);

    /**
     * 恢复。
     * <ul>
     *   <li>{@code PAUSED} 且采集线程还在 → <b>原地唤醒</b>，同一 run 继续，一页都不重采</li>
     *   <li>{@code PAUSED} 但线程已不在（异常情况）→ 新开一次运行</li>
     *   <li>{@code INTERRUPTED} → 新开一次运行，从游标续传</li>
     * </ul>
     */
    TaskRun resume(String taskId);

    /**
     * 取消。
     *
     * <p>分两种情况：
     * <ul>
     *   <li>有采集线程 → 置 CANCELED 后 {@code handle.cancel()}，线程会被唤醒并抛取消异常；
     *       run 的终态由 {@link com.bookcollector.task.TaskRunner} 写（它是所有者）</li>
     *   <li>没有线程（假死记录）→ 置 CANCELED 并顺手把 run 收尾，否则那条 run 会永远停在 RUNNING</li>
     * </ul>
     */
    void cancel(String taskId);
}
