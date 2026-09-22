package com.bookcollector.collector;

/**
 * 采集被主动取消。
 *
 * <p>用异常而不是返回值来表达「取消」，是为了能从 {@link CollectLoop} 的
 * 分页循环深处一次性跳出来，不用在每一层都写 {@code if (canceled) return;}。
 *
 * <p>它是<b>正常流程的一部分</b>，不是错误 —— 上层捕获后应该把 run 标成
 * {@code CANCELED}，而不是 {@code FAILED}。所以这里刻意<b>不打印堆栈</b>
 * （{@code fillInStackTrace} 被跳过），避免日志里出现吓人的调用栈。
 */
public class CollectCanceledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CollectCanceledException() {
        super("采集已被取消");
    }

    /** 取消是预期内的控制流，不需要堆栈信息 */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
