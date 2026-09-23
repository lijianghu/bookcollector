package com.bookcollector.audit.service.impl;

import com.bookcollector.audit.entity.ApiRequest;
import com.bookcollector.audit.repository.ApiRequestQueryRepository;
import com.bookcollector.audit.service.AuditService;
import com.bookcollector.common.PageResult;
import com.bookcollector.util.PageQueryUtil;

import org.springframework.stereotype.Service;

/**
 * {@link AuditService} 的实现。
 *
 * <p>这里只做参数归一化 + 转发，查询构造与排序在
 * {@link ApiRequestQueryRepository} 里（那才是「怎么和 Mongo 说话」）。
 * 归一化口径（默认 20、上限 200）统一取自 {@link PageQueryUtil}，全项目一个来源。
 */
@Service
public class AuditServiceImpl implements AuditService {

    private final ApiRequestQueryRepository repository;

    public AuditServiceImpl(ApiRequestQueryRepository repository) {
        this.repository = repository;
    }

    @Override
    public PageResult<ApiRequest> page(String runId, String targetType, String targetId,
                                       Boolean onlyFailed, Integer page, Integer size) {
        int p = PageQueryUtil.normalizePage(page);
        int s = PageQueryUtil.normalizeSize(size);
        return repository.pageQuery(runId, targetType, targetId, onlyFailed, p, s);
    }
}
