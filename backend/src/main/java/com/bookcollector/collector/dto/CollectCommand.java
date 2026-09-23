package com.bookcollector.collector.dto;

import com.bookcollector.collector.CollectControl;
import com.bookcollector.common.enums.TargetType;

import lombok.Builder;
import lombok.Getter;

/**
 * 一次采集的入参。
 *
 * <p>字段多到 6 个以上，用参数对象而不是方法签名 —— 否则调用处会变成
 * {@code run(a, b, c, null, d, false, e)} 这种读不懂的代码。
 */
@Getter
@Builder
public class CollectCommand {

    /** 归属的运行 ID（collect_task_runs.id）。用于进度上报与请求审计；可为 null */
    private String runId;

    /** 归属的任务 ID。用于日志归属；可为 null */
    private String taskId;

    /** 采集目标类型，必填 */
    private TargetType targetType;

    /** 采集目标 ID，必填。分类是 "300000"，榜单是 "rising" */
    private String targetId;

    /** 目标名称快照，如 "文学"。用于游标和日志展示 */
    private String targetName;

    /** 最大页数。{@code null} 或 {@code <= 0} 表示不限（一直翻到 hasMore=false） */
    private Integer maxPages;

    /** 是否忽略已有游标、从 0 开始重采。默认 false（续传） */
    private boolean resetCursor;

    /** 暂停/取消控制信号。为 null 时等价于「不停不取消」 */
    private CollectControl control;

    /** 采集来源标识，形如 {@code "category:300000"}。写入 Book.collectSource */
    public String sourceKey() {
        return targetType == null ? null
                : targetType.name().toLowerCase() + ":" + targetId;
    }

    /** 归一化后的最大页数：{@code <= 0} 一律当成「不限」，用 -1 表示 */
    public int effectiveMaxPages() {
        if (maxPages == null || maxPages <= 0) {
            return -1;
        }
        return maxPages;
    }

    public CollectControl controlOrDefault() {
        return control == null ? CollectControl.NOOP : control;
    }

    /** 入参自检。缺失必填项时早失败，别跑到一半才发现 */
    public void validate() {
        if (targetType == null) {
            throw new IllegalArgumentException("targetType 不能为空");
        }
        if (targetId == null || targetId.trim().isEmpty()) {
            throw new IllegalArgumentException("targetId 不能为空");
        }
    }
}
