package com.bookcollector.collector;

/**
 * 极简限速器：保证两次 {@link #acquire()} 之间至少间隔 {@code 1000/permitsPerSecond} 毫秒。
 *
 * <p>为什么不用 Guava 的 {@code RateLimiter}：
 * 只为这一个功能引 3MB 依赖不划算，而这里的语义简单到十行就能写对
 * （原 Python 版就是一句 {@code time.sleep(1)}）。
 *
 * <p>线程安全：{@code acquire()} 是 {@code synchronized} 的。
 * 本项目的采集任务是单线程顺序拉页，不存在并发争抢。
 */
public class SimpleRateLimiter {

    private final long intervalMillis;

    /** 下一次允许发起请求的时间戳（毫秒） */
    private long nextAllowedAt = 0L;

    /**
     * @param permitsPerSecond 每秒允许的请求数。1.0 = 每 1 秒一次
     */
    public SimpleRateLimiter(double permitsPerSecond) {
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException("permitsPerSecond 必须大于 0");
        }
        this.intervalMillis = (long) (1000.0D / permitsPerSecond);
    }

    /**
     * 取一个令牌。必要时阻塞到下一个允许的时间点。
     *
     * @throws InterruptedException 等待期间线程被中断（取消任务时会用到）
     */
    public synchronized void acquire() throws InterruptedException {
        long now = System.currentTimeMillis();
        if (now < nextAllowedAt) {
            long sleep = nextAllowedAt - now;
            Thread.sleep(sleep);
            now = System.currentTimeMillis();
        }
        // 用 now 而不是 nextAllowedAt 作为基准，避免长时间暂停后「补偿式连发」
        nextAllowedAt = now + intervalMillis;
    }

    /** 不阻塞，仅判断现在是否可以立刻发请求 */
    public synchronized boolean tryAcquire() {
        long now = System.currentTimeMillis();
        if (now >= nextAllowedAt) {
            nextAllowedAt = now + intervalMillis;
            return true;
        }
        return false;
    }

    public long getIntervalMillis() {
        return intervalMillis;
    }
}
