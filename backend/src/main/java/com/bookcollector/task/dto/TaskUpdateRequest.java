package com.bookcollector.task.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 编辑任务请求。
 *
 * <p>只有三个字段可改，且<b>没有</b> {@code targetType} / {@code targetId} ——
 * 目标不能改（它是游标的归属键）。这个类刻意不提供那两个字段，
 * 让「不能改」这件事在类型层面就成立，而不是靠 Service 里的一句 if 判断。
 */
@Schema(description = "编辑任务请求（只改传了的字段）")
public class TaskUpdateRequest {

    @Schema(description = "任务名")
    private String name;

    @Schema(description = "最大页数。0 = 不限制")
    private Integer maxPages;

    @Schema(description = "备注")
    private String remark;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(Integer maxPages) {
        this.maxPages = maxPages;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
