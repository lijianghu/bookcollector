package com.bookcollector.collector.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * 一次原始请求的结果。不抛异常，把成功/失败都装进来，由调用方决定怎么处理。
 */
@Getter
@Builder
@ToString(of = {"url", "statusCode", "costMs"})
public class WereadRawResponse {

    /** 实际请求的 URL */
    private final String url;

    /** HTTP 状态码；-1 表示请求根本没发出去（网络层异常） */
    private final int statusCode;

    /** 耗时（毫秒） */
    private final long costMs;

    /** 响应体原文；失败时为 null */
    private final String body;

    /** 失败原因；成功时为 null */
    private final Throwable error;

    public boolean isSuccess() {
        return error == null && statusCode == 200 && body != null;
    }

    public boolean isRetryable() {
        // 网络层异常，或服务端 5xx / 429，都值得重试
        return error != null || statusCode == -1 || statusCode >= 500 || statusCode == 429;
    }

    /**
     * 把一次失败翻译成一句人话 —— 写进采集日志、{@code errorMsg} 和请求审计。
     *
     * <p>放在这里而不是各自实现，是因为这三个地方必须说同一句话：
     * 用户在界面上看到「任务失败：第 1 页采集失败：HTTP 500」，
     * 点进请求审计看到的也应该是 {@code HTTP 500}，而不是空或另一种措辞。
     *
     * @return 成功时返回 {@code null}
     */
    public String describeFailure() {
        if (isSuccess()) {
            return null;
        }
        if (error != null) {
            String msg = error.getMessage();
            return "网络异常 " + error.getClass().getSimpleName() + (msg == null ? "" : "：" + msg);
        }
        return "HTTP " + statusCode;
    }
}
