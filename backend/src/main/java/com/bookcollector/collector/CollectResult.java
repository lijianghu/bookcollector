package com.bookcollector.collector;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * 一次采集的结果。
 *
 * <p>注意 {@link #errorMsg} 为 null 且 {@link #canceled} 为 false 才算「正常跑完」。
 * 上层据此决定把 run 标成 SUCCESS / FAILED / CANCELED。
 */
@Getter
@Builder
@ToString
public class CollectResult {

    /** 成功采集的页数 */
    private final int pagesDone;

    /** 解析出的图书条数（含后续 upsert 失败的） */
    private final int booksParsed;

    /** 实际写入/更新的图书条数 */
    private final int booksSaved;

    /** 结束时停留在的游标值（= 下一页要用的 maxIndex） */
    private final int currentCursor;

    /** 接口是否还有更多数据。false 表示已采到底 */
    private final boolean hasMore;

    /** 接口报告的该目标总书数 */
    private final Integer totalCount;

    /** 是否被主动取消 */
    private final boolean canceled;

    /** 失败原因。成功时为 null */
    private final String errorMsg;

    /** 总耗时（毫秒） */
    private final long costMs;

    public boolean isSuccess() {
        return errorMsg == null && !canceled;
    }

    public boolean isFailed() {
        return errorMsg != null;
    }
}
