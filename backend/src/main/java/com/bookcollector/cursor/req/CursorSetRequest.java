package com.bookcollector.cursor.req;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.validation.constraints.NotBlank;

/**
 * 指定游标起点请求（FR-E2 / FR-E3）。
 *
 * <p>{@code maxIndex} 传 {@code 0}（或不传）就是「重置为 0」——
 * 前端不需要为「重置」和「指定起点」做两个按钮。
 */
@Schema(description = "指定游标起点请求")
public class CursorSetRequest {

    @Schema(description = "目标类型：CATEGORY / RANKING", example = "CATEGORY", required = true)
    @NotBlank(message = "不能为空")
    private String targetType;

    @Schema(description = "目标 ID", example = "300000", required = true)
    @NotBlank(message = "不能为空")
    private String targetId;

    @Schema(description = "目标名称快照，可选")
    private String targetName;

    @Schema(description = "要指定的游标值（= 下一页请求用的 maxIndex）。0 或不传 = 重置为 0", example = "100")
    private Integer maxIndex;

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

    public Integer getMaxIndex() {
        return maxIndex;
    }

    public void setMaxIndex(Integer maxIndex) {
        this.maxIndex = maxIndex;
    }
}
