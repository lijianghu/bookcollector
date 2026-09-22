package com.bookcollector.taxonomy.entity;

import com.bookcollector.common.enums.TargetType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.util.Date;

/**
 * 采集目标字典 —— 21 个分类 + 7 个榜单 = 28 条。
 *
 * <p>数据来源是原 {@code config.py} 的 {@code CATEGORIES} / {@code RANKING_LISTS}，
 * 由 {@code SeedRunner} 在启动时幂等写入（已存在则保留用户改过的 {@code enabled}）。
 *
 * <p><b>为什么字典要落库而不是写死在代码里</b>：前端需要一个「可选目标列表」，
 * 而且以后想停掉某个分类（{@code enabled=false}）不该改代码重新打包。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "taxonomy_config")
public class TaxonomyItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    /** 类型：CATEGORY / RANKING，取值见 {@link TargetType} */
    private String type;

    /**
     * 编码。CATEGORY 用数字字符串（"300000"），RANKING 用英文串（"rising"）。
     * 统一存字符串，因为要拼进 URL 且两类格式不同。
     */
    private String code;

    /** 展示名："文学" / "飙升榜" */
    private String name;

    /** 是否启用。停用的目标不出现在「新建任务」的可选项里 */
    private Boolean enabled;

    /** 排序号，从 1 开始，决定前端下拉框的顺序 */
    private Integer sort;

    /** 备注 */
    private String remark;

    /** seed 写入时间。由审计自动填充（SeedRunner 用 insertAll，走实体路径，审计生效） */
    @CreatedDate
    private Date createdAt;

    @LastModifiedDate
    private Date updatedAt;
}
