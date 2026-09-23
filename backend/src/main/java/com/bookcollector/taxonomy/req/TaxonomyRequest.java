package com.bookcollector.taxonomy.req;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 字典项的新增 / 编辑请求（FR-C1 / FR-C2）。
 *
 * <p>分类和榜单共用这一个 DTO —— 两者的字段完全一样，差别只在
 * {@code type}（由 URL 路径决定，不在请求体里）。分成两个类只会产生
 * 两份必然同步修改的重复代码。
 *
 * <p>全部字段用包装类型，{@code null} 表示「不修改」（编辑场景）。
 */
@Schema(description = "字典项请求")
public class TaxonomyRequest {

    @Schema(description = "编码。CATEGORY 用数字串（300000），RANKING 用英文串（rising）。"
            + "新增时必填；编辑时不允许修改")
    private String code;

    @Schema(description = "展示名，如「文学」「飙升榜」")
    private String name;

    @Schema(description = "是否启用。停用的目标不出现在「新建任务」的可选项里")
    private Boolean enabled;

    @Schema(description = "排序号，从 1 开始。不传则自动排到当前类型末尾")
    private Integer sort;

    @Schema(description = "备注")
    private String remark;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
