package com.bookcollector.audit;

import com.bookcollector.audit.entity.ApiRequest;
import com.bookcollector.common.PageResult;
import com.bookcollector.common.ResultBean;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 请求审计（FR-G2）。
 *
 * <h3>这张表是排查「某页为什么没数据」的第一现场</h3>
 * 采集出问题时，能回答「到底发了几次请求、每次什么状态码、返回几条」的
 * 只有 {@code api_requests}。所以列表默认按时间倒序，且支持
 * {@code onlyFailed=true} 一键过滤出异常请求。
 *
 * <p>只有查询，没有删除。审计记录的价值恰恰在于「事后还在」——
 * 提供一个「清空审计」按钮，早晚会有人手滑点掉正在排查的那批数据。
 * 要清理请直接连库操作。
 */
@Tag(name = "08. 请求审计", description = "微信读书接口调用记录")
@RestController
@RequestMapping("/api/requests")
public class RequestAuditController {

    private final ApiRequestQueryRepository repository;

    public RequestAuditController(ApiRequestQueryRepository repository) {
        this.repository = repository;
    }

    @Operation(summary = "请求审计列表",
            description = "分页，按请求时间倒序。可按 runId / targetType / targetId 筛选，"
                    + "onlyFailed=true 只看失败请求")
    @GetMapping
    public ResultBean<PageResult<ApiRequest>> page(
            @Parameter(description = "按运行 ID 筛选") @RequestParam(required = false) String runId,
            @Parameter(description = "目标类型：CATEGORY / RANKING") @RequestParam(required = false) String targetType,
            @Parameter(description = "目标 ID") @RequestParam(required = false) String targetId,
            @Parameter(description = "只看失败（状态码非 200 或有错误摘要）") @RequestParam(required = false) Boolean onlyFailed,
            @Parameter(description = "页码，从 1 开始") @RequestParam(required = false) Integer page,
            @Parameter(description = "每页条数，默认 20，上限 200") @RequestParam(required = false) Integer size) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 20 : Math.min(size, 200);
        return ResultBean.ok(repository.pageQuery(runId, targetType, targetId, onlyFailed, p, s));
    }
}
