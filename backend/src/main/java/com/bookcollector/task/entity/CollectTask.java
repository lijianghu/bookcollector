package com.bookcollector.task.entity;

import com.bookcollector.common.enums.TaskStatus;
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
 * 采集任务定义。原 Python 没有这个概念（它是一次性脚本），是本次改造新增的。
 *
 * <p>一个任务 = 一个「目标」（某分类 或 某榜单）+ 一个「最大页数」。
 * 任务本身只是配置，真正执行产生的是 {@link TaskRun}。
 *
 * <p><b>为什么把 targetName 冗余存一份</b>：任务列表页要显示「文学」「飙升榜」，
 * 如果不冗余，每行都要回查 {@code taxonomy_config}。字典是低频变更数据，
 * 冗余的维护成本远低于查询成本。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "collect_tasks")
public class CollectTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    /** 任务名，如「文学-前10页」 */
    private String name;

    /** 采集目标类型：CATEGORY / RANKING */
    private String targetType;

    /** 采集目标 ID："300000" / "rising" */
    private String targetId;

    /** 目标名称快照："文学" / "飙升榜" */
    private String targetName;

    /**
     * 最大页数。{@code 0} 或 {@code null} 表示不限制（一直翻到 hasMore=false）。
     * 注意：不限页数的分类（如文学有 54078 本、约 2704 页）会跑很久，默认给个有限值。
     */
    private Integer maxPages;

    /** 任务状态，取值见 {@link TaskStatus} */
    private String status;

    /** 备注 */
    private String remark;

    /** 最近一次运行的 runId */
    private String lastRunId;

    /** 累计运行次数 */
    private Integer runCount;

    @CreatedDate
    private Date createdAt;

    @LastModifiedDate
    private Date updatedAt;
}
