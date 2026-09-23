package com.bookcollector.audit.service;

import com.bookcollector.audit.entity.ApiRequest;
import com.bookcollector.common.PageResult;

/**
 * 请求审计的查询业务（FR-G2）。
 *
 * <h3>为什么这里只有查询，没有删除</h3>
 * 审计记录的价值恰恰在于「事后还在」。提供一个「清空审计」入口，早晚会有人
 * 手滑点掉正在排查的那批数据。要清理请直接连库操作。
 *
 * <p>页码与每页条数的夹紧（{@code page>=1}、{@code size<=200}）在实现里，
 * 不让前端传 {@code size=100000} 把服务打爆。
 *
 * @see com.bookcollector.audit.service.impl.AuditServiceImpl
 */
public interface AuditService {

    /**
     * 分页查询（按 {@code createdAt} 倒序，{@code _id} 升序兜底）。
     *
     * @param runId      归属运行 ID，可空
     * @param targetType CATEGORY / RANKING，可空
     * @param targetId   目标 ID，可空
     * @param onlyFailed true = 只看失败（{@code statusCode != 200} 或 errorMsg 非空）
     * @param page       页码，从 1 开始；null 或 &lt;1 归一为 1
     * @param size       每页条数，默认 20，上限 200
     */
    PageResult<ApiRequest> page(String runId, String targetType, String targetId,
                                Boolean onlyFailed, Integer page, Integer size);
}
