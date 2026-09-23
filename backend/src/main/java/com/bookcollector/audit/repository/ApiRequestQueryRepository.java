package com.bookcollector.audit.repository;

import com.bookcollector.audit.entity.ApiRequest;
import com.bookcollector.common.PageResult;
import com.bookcollector.util.PageQueryUtil;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
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
     * <p>分页样板（count 短路 / 排序 / skip+limit）统一走 {@link PageQueryUtil}；
     * 本类只负责「条件怎么拼」和「按什么排」。
     *
     * @param runId       归属运行 ID，可空
     * @param targetType  CATEGORY / RANKING，可空
     * @param targetId    目标 ID，可空
     * @param onlyFailed  true = 只看失败（{@code statusCode != 200} 或 errorMsg 非空）
     */
    public PageResult<ApiRequest> pageQuery(String runId, String targetType, String targetId,
                                            Boolean onlyFailed, int page, int size) {
        // 主排序 createdAt 倒序（新的在前）+ _id 升序兜底，
        // 保证同一毫秒写入的多条记录翻页不重不漏
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt")
                .and(Sort.by(Sort.Direction.ASC, "_id"));
        return PageQueryUtil.getPageResult(mongoTemplate, page, size,
                build(runId, targetType, targetId, onlyFailed), ApiRequest.class, sort);
    }

    /** 把筛选条件拼成一个 Criteria（空条件返回空 Criteria，等价于「全部」） */
    private Criteria build(String runId, String targetType, String targetId, Boolean onlyFailed) {
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

        if (conds.isEmpty()) {
            return new Criteria();
        }
        if (conds.size() == 1) {
            return conds.get(0);
        }
        return new Criteria().andOperator(conds.toArray(new Criteria[0]));
    }

    private boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
