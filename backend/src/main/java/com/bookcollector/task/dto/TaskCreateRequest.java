package com.bookcollector.task.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.validation.constraints.NotBlank;

/**
 * 新建任务请求（FR-D2）。
 *
 * <p>{@code targetType} / {@code targetId} 必填且是<b>身份</b> ——
 * 它们和 {@code collect_cursors} 的主键绑定，创建后不可修改。
 * {@code targetName} 只是展示快照，不传就用 {@code targetId} 兜底。
 */
@Schema(description = "新建任务请求")
public class TaskCreateRequest {

    @Schema(description = "任务名。不传则自动生成「<目标名>-采集」")
    private String name;

    @Schema(description = "目标类型：CATEGORY / RANKING", example = "CATEGORY", required = true)
    @NotBlank(message = "不能为空")
    private String targetType;

    @Schema(description = "目标 ID：分类用数字串（300000），榜单用英文串（rising）",
            example = "300000", required = true)
    @NotBlank(message = "不能为空")
    private String targetId;

    @Schema(description = "目标名称快照，如「文学」。不传则用 targetId 兜底")
    private String targetName;

    @Schema(description = "最大页数。0 或不传 = 不限制（一直翻到 hasMore=false）", example = "10")
    private Integer maxPages;

    @Schema(description = "备注")
    private String remark;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
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
