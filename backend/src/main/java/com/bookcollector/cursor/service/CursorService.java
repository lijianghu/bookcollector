package com.bookcollector.cursor.service;

import com.bookcollector.cursor.entity.CollectCursor;
import com.bookcollector.cursor.req.CursorSetRequest;

import java.util.List;
import java.util.Map;

/**
 * 采集游标 —— 断点续传（FR-E1 ~ FR-E3）。
 *
 * <h3>⚠️ 重置游标不会删已采到的书</h3>
 * 这是最容易误解的一点，所以 {@link #set} 的返回值里显式带上 {@code note} 说明。
 * 重置后再跑任务，那些书会被 upsert 更新（幂等），不会重复插入，也不会变少。
 * 想清库得另外调 {@code DELETE /api/books} 之类的接口 —— 本接口不做这件事。
 *
 * <h3>⚠️ 运行中重置游标是「无效」的</h3>
 * 一次运行的起始游标在 {@code launch()} 时就固化进 {@code CollectCommand} 了，
 * 而且采集线程每采完一页就 {@code advance()} 一次 —— 会把手工设置的值覆盖掉。
 * 这里<b>不做拦截</b>：判断「是否在跑」需要引入 {@code TaskRegistry} 依赖，
 * 而它对这个纯字典操作是多余的耦合。改为在响应里说明。
 * 正确姿势：先取消任务，再重置游标，再启动。
 *
 * @see com.bookcollector.cursor.service.impl.CursorServiceImpl
 */
public interface CursorService {

    /** 游标列表，按最近更新时间倒序 */
    List<CollectCursor> list();

    /**
     * 重置 / 指定游标起点。
     *
     * <p>{@code maxIndex=0} 或不传 = 重置为 0；{@code >0} = 从该位置继续。
     *
     * @return {@code targetType} / {@code targetId} / {@code maxIndex} /
     *         {@code created}（是否新建）/ {@code reset}（是否重置为 0）/ {@code note}
     */
    Map<String, Object> set(CursorSetRequest req);

    /**
     * 删除游标。删除后该目标会被视为「从未采集过」，下次启动任务从 {@code maxIndex=0} 开始。
     *
     * @return {@code id} / {@code targetType} / {@code targetId} / {@code deleted}
     */
    Map<String, Object> delete(String id);
}
