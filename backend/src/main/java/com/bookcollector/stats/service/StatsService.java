package com.bookcollector.stats.service;

import java.util.Map;

/**
 * 仪表盘统计（FR-F1 / FR-F2）。
 *
 * <h3>为什么用聚合管道而不是「查出全部再在 Java 里算」</h3>
 * 图书量预期 1 万–5 万条。把 5 万份文档（每份 46 个字段）拉到 JVM 里只为数几个桶，
 * 是纯粹的浪费：网络传输、反序列化、GC 全都白干。聚合在 MongoDB 里跑，
 * 回来的只有几十行结果。
 *
 * <h3>🔴 「今日采集数」的口径：按 {@code firstCollectedAt}（净新增）</h3>
 * 不按 {@code lastCollectedAt}。理由：{@code lastCollectedAt} 每次 upsert 都会刷新，
 * 重采同一批书会让它「今天又采了一万本」，而实际一本都没新增。
 * 用 {@code firstCollectedAt} 得到的是「今天库里多了多少本之前没有的书」——
 * 这才是用户看到这个数字时的预期。图表「近 7 天采集量」用同一个口径，
 * 保证指标卡和图表不会互相矛盾。
 *
 * @see com.bookcollector.stats.service.impl.StatsServiceImpl
 */
public interface StatsService {

    /**
     * 指标卡（FR-F1）。
     *
     * <p>4 个主指标是 {@code bookTotal} / {@code categoryTotal} / {@code taskTotal} /
     * {@code todayCollected}。其余字段是顺手带上的上下文 —— 它们都是「打开仪表盘时
     * 想知道但不想再发一次请求」的信息（有没有任务在跑、最近一次采集是什么时候）。
     */
    Map<String, Object> overview();

    /** 4 张图（FR-F2）：分类分布 / 评分分布 / 出版年份趋势 / 近 7 天采集量 */
    Map<String, Object> charts();
}
