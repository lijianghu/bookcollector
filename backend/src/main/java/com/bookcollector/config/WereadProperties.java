package com.bookcollector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微信读书接口相关配置。对应 application.yml 的 {@code bookcollector.weread.*}。
 */
@Component
@ConfigurationProperties(prefix = "bookcollector.weread")
public class WereadProperties {

    /** 列表接口基础地址 */
    private String baseUrl = "https://weread.qq.com/web/bookListInCategory";

    /** Referer，微信读书会校验 */
    private String referer = "https://weread.qq.com/";

    /** 连接超时（毫秒） */
    private int connectTimeout = 10_000;

    /** 读取超时（毫秒） */
    private int readTimeout = 30_000;

    /** 限速：每秒允许的请求数。1 = 每页间隔 1 秒（沿用原 Python 的 time.sleep(1)） */
    private double rateLimitPerSecond = 1.0;

    /** 单页失败重试次数 */
    private int maxRetry = 3;

    /** 是否启用请求审计落库 */
    private boolean auditEnabled = true;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getReferer() {
        return referer;
    }

    public void setReferer(String referer) {
        this.referer = referer;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public int getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

    public double getRateLimitPerSecond() {
        return rateLimitPerSecond;
    }

    public void setRateLimitPerSecond(double rateLimitPerSecond) {
        this.rateLimitPerSecond = rateLimitPerSecond;
    }

    public int getMaxRetry() {
        return maxRetry;
    }

    public void setMaxRetry(int maxRetry) {
        this.maxRetry = maxRetry;
    }

    public boolean isAuditEnabled() {
        return auditEnabled;
    }

    public void setAuditEnabled(boolean auditEnabled) {
        this.auditEnabled = auditEnabled;
    }
}
