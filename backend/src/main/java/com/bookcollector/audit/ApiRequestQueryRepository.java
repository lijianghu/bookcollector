package com.bookcollector.audit;

import com.bookcollector.audit.entity.ApiRequest;
import com.bookcollector.common.PageResult;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求审计的查询（FR-G2）。
 *
 * <h3>为什么查询单独一个类，不塞进写入用的 {@link ApiRequestRepository}</h3>
 * 写入那个类有一个非常明确的约束：<b>它只插不改，且失败不抛异常</b>
 * （因为审计不能影响采集主流程）。查询的约束完全相反 —— 它要能被上层的
 * 异常处理器正常兜住，失败了应该报错。两者的「错误处理哲学」不同，
 * 混在一个类里迟早有人把 {@code try-catch 吞掉} 复制到查询方法上。
 */
@Repository
public class ApiRequestQueryRepository {

    private final MongoTemplate mongoTemplate;

    public ApiRequestQueryRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * 分页查询。
     *
     * @param runId       归属运行 ID，可空
     * @param targetType  CATEGORY / RANKING，可空
     * @param targetId    目标 ID，可空
     * @param onlyFailed  true = 只看失败（{@code statusCode != 200} 或 errorMsg 非空）
     */
    public PageResult<ApiRequest> pageQuery(String runId, String targetType, String targetId,
                                            Boolean onlyFailed, int page, int size) {
        Query query = build(runId, targetType, targetId, onlyFailed);
        long total = mongoTemplate.count(query, ApiRequest.class);
        if (total == 0L) {
            return PageResult.empty(page, size);
        }

        query.with(Sort.by(Sort.Direction.DESC, "createdAt")
                .and(Sort.by(Sort.Direction.ASC, "_id")));
        query.with(PageRequest.of(page - 1, size));
        List<ApiRequest> list = mongoTemplate.find(query, ApiRequest.class);
        return PageResult.of(list, total, page, size);
    }

    private Query build(String runId, String targetType, String targetId, Boolean onlyFailed) {
        List<Criteria> conds = new ArrayList<Criteria>();
        if (notBlank(runId)) {
            conds.add(Criteria.where("runId").is(runId.trim()));
        }
        if (notBlank(targetType)) {
            conds.add(Criteria.where("targetType").is(targetType.trim().toUpperCase()));
        }
        if (notBlank(targetId)) {
            conds.add(Criteria.where("targetId").is(targetId.trim()));
        }
        if (Boolean.TRUE.equals(onlyFailed)) {
            // 两个判据是「或」关系：HTTP 非 200，或者解析/业务层记了错误摘要。
            // 有些失败是「HTTP 200 但 body 里没有 books 数组」，只看状态码会漏掉它们。
            conds.add(new Criteria().orOperator(
                    Criteria.where("statusCode").ne(200),
                    Criteria.where("errorMsg").ne(null)));
        }

        Query query = new Query();
        if (conds.size() == 1) {
            query.addCriteria(conds.get(0));
        } else if (conds.size() > 1) {
            query.addCriteria(new Criteria().andOperator(conds.toArray(new Criteria[0])));
        }
        return query;
    }

    private boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
