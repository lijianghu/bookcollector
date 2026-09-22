package com.bookcollector.collector;

/**
 * 采集过程的控制信号 —— 暂停 / 取消。
 *
 * <p><b>为什么是个接口而不是直接用 TaskRegistry</b>：
 * {@link CollectLoop} 是纯采集逻辑，不该知道「任务」「内存注册表」这些编排概念。
 * S2 阶段传 {@link #NOOP}（不停不取消），S3 阶段换成 TaskRegistry 的实现即可，
 * {@code CollectLoop} 一行都不用改。
 *
 * <p>暂停用「阻塞」而不是「抛异常」表达，是因为暂停后要能<b>原地恢复</b> ——
 * 抛出异常会把栈展开，恢复就得从头再来。
 */
public interface CollectControl {

    /** 什么都不做的实现，S2 阶段用这个 */
    CollectControl NOOP = new CollectControl() {
        @Override
        public boolean isCanceled() {
            return false;
        }

        @Override
        public void awaitIfPaused() throws InterruptedException {
            // 不暂停
        }
    };

    /** 是否已被请求取消 */
    boolean isCanceled();

    /**
     * 如果处于暂停状态就<b>在此阻塞</b>，直到恢复或被取消。
     * 实现方需要保证被取消时能从这里返回（随后 {@link #isCanceled()} 会返回 true）。
     *
     * <p>声明 {@code InterruptedException} 是必须的：暂停的本质是阻塞，而阻塞就该能被中断。
     * 如果这里把中断吞掉（catch 后只 set interrupt 标志再返回），
     * {@link CollectLoop} 的循环会带着中断标志继续往下跑，后续的
     * {@code Thread.sleep} / 限速器会立刻抛异常 —— 表现是「任务莫名失败」，
     * 而不是「任务被停止」。所以中断必须如实向上抛。
     */
    void awaitIfPaused() throws InterruptedException;

    /**
     * 每个分页循环的开头调用一次。
     *
     * @throws CollectCanceledException 已被取消
     * @throws InterruptedException     阻塞期间线程被中断
     */
    default void checkPoint() throws InterruptedException {
        awaitIfPaused();
        if (isCanceled()) {
            throw new CollectCanceledException();
        }
    }
}
