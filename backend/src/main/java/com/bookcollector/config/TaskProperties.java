package com.bookcollector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 任务编排相关配置。对应 application.yml 的 {@code bookcollector.task.*}。
 */
@Component
@ConfigurationProperties(prefix = "bookcollector.task")
public class TaskProperties {

    /**
     * 同时允许运行的采集任务数。
     *
     * <p>默认 <b>1</b>。理由（对齐 2.5 非功能需求「同时最多 1 个采集任务在跑」）：
     * <ul>
     *   <li>接口限速是<b>全局</b>的（1 秒/页），并发跑两个任务等于把请求频率翻倍，
     *       有被微信读书限流/封禁的风险；</li>
     *   <li>单机单用户，没有「必须同时采两个分类」的诉求。</li>
     * </ul>
     * 调大它之前先想清楚限速这件事。
     */
    private int maxConcurrentRuns = 1;

    /** 线程池队列容量。队列满了直接拒绝（不阻塞 HTTP 线程） */
    private int queueCapacity = 16;

    /** 运行记录列表默认返回条数 */
    private int runListLimit = 50;

    /** 运行日志列表默认返回条数 */
    private int logListLimit = 500;

    public int getMaxConcurrentRuns() {
        return maxConcurrentRuns;
    }

    public void setMaxConcurrentRuns(int maxConcurrentRuns) {
        this.maxConcurrentRuns = maxConcurrentRuns;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getRunListLimit() {
        return runListLimit;
    }

    public void setRunListLimit(int runListLimit) {
        this.runListLimit = runListLimit;
    }

    public int getLogListLimit() {
        return logListLimit;
    }

    public void setLogListLimit(int logListLimit) {
        this.logListLimit = logListLimit;
    }
}
