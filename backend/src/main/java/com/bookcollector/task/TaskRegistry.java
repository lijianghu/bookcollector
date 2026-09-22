package com.bookcollector.task;

import com.bookcollector.common.BizException;
import com.bookcollector.common.ResultBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行中任务的<b>内存注册表</b> —— 同时承担两件事：
 *
 * <h3>1. 并发锁（FR-D6：同一目标不允许并发）</h3>
 * key = {@code targetType:targetId}。启动前 {@link #acquire} 抢占，
 * 冲突直接抛 {@link BizException}，把「谁在占着」写进报错信息 ——
 * 用户看到的是「目标「文学」正在采集中（任务 文学-前10页），请先停止它」，
 * 而不是一句无信息量的「启动失败」。
 *
 * <h3>2. 暂停/取消信号的存放点</h3>
 * {@link TaskHandle} 放在这里，HTTP 线程（收到 pause 请求）和采集线程
 * （{@code CollectLoop} 里查信号）通过它通信。
 *
 * <h3>🔴 为什么必须在 ContextClosedEvent 时把所有 handle 取消掉</h3>
 * 暂停中的采集线程<b>阻塞在</b> {@link TaskHandle#awaitIfPaused()} 里，
 * 不会自己退出。关停时如果不管它，Spring 的线程池会一直等到
 * {@code awaitTerminationSeconds} 超时才强杀 —— 每次重启都要白等 20 秒，
 * 而且任务是被强杀的，日志里看不出原因。
 *
 * <p>监听 {@link ContextClosedEvent} 而不是用 {@code @PreDestroy}：
 * Spring 的关闭顺序是「发布 ContextClosedEvent → {@code lifecycleProcessor.onClose()}
 * （停线程池）→ destroyBeans()（执行 @PreDestroy）」。用 {@code @PreDestroy} 就晚了 ——
 * 那时线程池已经在等我们了。用事件则刚好在停池<b>之前</b>把线程放出来。
 *
 * <p>取消后这些 run 会被 {@link InterruptedTaskDetector} 在下次启动时标成
 * {@code INTERRUPTED}，游标保留，用户可以直接恢复。
 */
@Component
public class TaskRegistry {

    private static final Logger log = LoggerFactory.getLogger(TaskRegistry.class);

    private final Map<String, TaskHandle> active = new ConcurrentHashMap<String, TaskHandle>();

    /** 拼 key。统一在这里拼，避免各处手写字符串拼错 */
    public static String keyOf(String targetType, String targetId) {
        return targetType + ":" + targetId;
    }

    /**
     * 抢占某个目标的采集权。
     *
     * @param targetLabel 目标名（如「文学」），仅用于报错信息
     * @throws BizException 该目标已被占用
     */
    public TaskHandle acquire(String taskId, String runId,
                              String targetType, String targetId, String targetLabel) {
        String key = keyOf(targetType, targetId);
        TaskHandle handle = new TaskHandle(key, taskId, runId, targetLabel);
        TaskHandle existing = active.putIfAbsent(key, handle);
        if (existing != null) {
            throw new BizException(ResultBean.code_warn,
                    "目标「" + targetLabel + "」正在采集中（任务 " + existing.taskId()
                            + "），请先停止它再启动新任务");
        }
        log.info("已占用采集权：{}（task={} run={}）", key, taskId, runId);
        return handle;
    }

    /** 释放。必须放在 finally 里 */
    public void release(String key) {
        TaskHandle removed = active.remove(key);
        if (removed != null) {
            log.info("已释放采集权：{}（run={}）", key, removed.runId());
        }
    }

    /** 查某个目标是否在采集。返回 null 表示空闲 */
    public TaskHandle find(String targetType, String targetId) {
        return active.get(keyOf(targetType, targetId));
    }

    public TaskHandle findByTaskId(String taskId) {
        for (TaskHandle h : active.values()) {
            if (h.taskId().equals(taskId)) {
                return h;
            }
        }
        return null;
    }

    /** 当前活跃（占用采集权）的目标数 */
    public int activeCount() {
        return active.size();
    }

    public List<TaskHandle> listActive() {
        return Collections.unmodifiableList(new ArrayList<TaskHandle>(active.values()));
    }

    public boolean isBusy(String targetType, String targetId) {
        return active.containsKey(keyOf(targetType, targetId));
    }

    /**
     * 关停时把所有 handle 取消，唤醒阻塞中的采集线程。
     *
     * <p>见类注释：必须用 {@link ContextClosedEvent} 而不是 {@code @PreDestroy}。
     */
    @EventListener(ContextClosedEvent.class)
    public void cancelAllOnShutdown() {
        if (active.isEmpty()) {
            return;
        }
        log.warn("应用正在关闭，取消 {} 个运行中的采集任务（游标已保留，下次启动可恢复）",
                active.size());
        for (TaskHandle h : active.values()) {
            h.cancel();
        }
    }
}
