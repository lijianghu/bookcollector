package com.bookcollector.audit.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.util.Date;

/**
 * 请求审计记录。原 MySQL 的 {@code api_requests} 表。
 *
 * <p>每成功/失败地打完一次微信读书接口，就落一条。用途：
 * <ul>
 *   <li>排查「某页为什么没数据」——能看到 statusCode / resultCount / errorMsg</li>
 *   <li>观测接口健康度——responseTimeMs 的分布</li>
 * </ul>
 *
 * <p>写入量大（21 分类 × 数百页），所以只做「插入」，不做更新，也不建太多索引。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "api_requests")
public class ApiRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    /** 归属的运行 ID（collect_task_runs.id）。手工探针触发时为空 */
    private String runId;

    /** 采集目标类型：CATEGORY / RANKING */
    private String targetType;

    /** 采集目标 ID："300000" / "rising" */
    private String targetId;

    /** 第几页，从 1 开始（人看的） */
    private Integer pageIndex;

    /** 本次请求用的 maxIndex（游标值，机器看的） */
    private Integer maxIndex;

    /** 完整请求 URL */
    private String url;

    /** HTTP 状态码 */
    private Integer statusCode;

    /** 耗时（毫秒） */
    private Integer responseTimeMs;

    /** 响应里的 synckey */
    private Long synckey;

    /** 响应里的 totalCount（该分类/榜单的总书数） */
    private Integer totalCount;

    /** 响应里的 hasMore */
    private Boolean hasMore;

    /** 本页返回的图书条数 */
    private Integer resultCount;

    /** 失败时的错误摘要。成功时为 null */
    private String errorMsg;

    /** 请求时间。走 {@code save()} 时由审计自动填充 */
    @CreatedDate
    private Date createdAt;
}
