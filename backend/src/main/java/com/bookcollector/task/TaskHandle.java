package com.bookcollector.task;

import com.bookcollector.collector.CollectControl;

import java.util.Date;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 一个正在运行的采集任务的<b>控制句柄</b> —— 暂停 / 恢复 / 取消的落点。
 *
 * <h3>为什么暂停用「阻塞」而不是「抛异常」</h3>
 * 抛异常会把调用栈展开，{@link com.bookcollector.collector.CollectLoop} 的局部变量
 * （本页已采到哪、累计写了几本）全部丢失，恢复就只能从页首重来。
 * 阻塞在 {@link #awaitIfPaused()} 里则能<b>原地恢复</b>，一页都不会重采。
 *
 * <h3>为什么用 {@link ReentrantLock} + {@link Condition} 而不是 {@code synchronized/wait}</h3>
 * 需要一个「可被打断的等待」：{@link Condition#await()} 会在中断时抛
 * {@code InterruptedException}，而 {@code Object.wait()} 也可以，但配合
 * {@link ReentrantLock} 的 {@code lockInterruptibly()} 语义更清楚，
 * 且 {@code signalAll()} 能一次性唤醒所有等待者（不会漏）。
 *
 * <h3>状态可见性</h3>
 * {@link #paused} / {@link #canceled} 是 {@code volatile}：写方在锁内，
 * 但 {@link #isCanceled()} 会被采集线程<b>无锁</b>调用（每页至少一次），
 * 必须保证可见性。
 */
public class TaskHandle implements CollectControl {

    /** 唯一键：{@code targetType:targetId} */
    private final String key;

    private final String taskId;

    private final String runId;

    /** 目标名，仅用于日志与报错信息 */
    private final String targetLabel;

    private final Date createdAt = new Date();

    private final ReentrantLock lock = new ReentrantLock();

    private final Condition resumed = lock.newCondition();

    private volatile boolean paused;

    private volatile boolean canceled;

    public TaskHandle(String key, String taskId, String runId, String targetLabel) {
        this.key = key;
        this.taskId = taskId;
        this.runId = runId;
        this.targetLabel = targetLabel;
    }

    // ------------------------------------------------------------------
    // 控制动作
    // ------------------------------------------------------------------

    /** 请求暂停。采集线程会在下一次 {@link #awaitIfPaused()} 处阻塞 */
    public void pause() {
        lock.lock();
        try {
            if (canceled) {
                return;
            }
            paused = true;
        } finally {
            lock.unlock();
        }
    }

    /** 恢复。唤醒阻塞中的采集线程 */
    public void resume() {
        lock.lock();
        try {
            paused = false;
            resumed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 请求取消。会<b>同时解除暂停</b> —— 否则一个暂停中的任务永远等不到
     * {@link #awaitIfPaused()} 返回，也就永远无法结束。
     */
    public void cancel() {
        lock.lock();
        try {
            canceled = true;
            paused = false;
            resumed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------
    // CollectControl
    // ------------------------------------------------------------------

    @Override
    public boolean isCanceled() {
        return canceled;
    }

    @Override
    public void awaitIfPaused() throws InterruptedException {
        // 先快速路径：绝大多数时候没暂停，不加锁直接返回，避免每页一次锁开销
        if (!paused || canceled) {
            return;
        }
        lock.lockInterruptibly();
        try {
            // 必须用 while 而不是 if：wait/signal 存在「虚假唤醒」，
            // 且醒来后可能已经被重新暂停（pause 后又 pause）
            while (paused && !canceled) {
                resumed.await();
            }
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------

    public String key() {
        return key;
    }

    public String taskId() {
        return taskId;
    }

    public String runId() {
        return runId;
    }

    public String targetLabel() {
        return targetLabel;
    }

    public Date createdAt() {
        return createdAt;
    }

    public boolean isPaused() {
        return paused;
    }

    @Override
    public String toString() {
        return "TaskHandle{" + key + ", task=" + taskId + ", run=" + runId
                + ", paused=" + paused + ", canceled=" + canceled + "}";
    }
}
