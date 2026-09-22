package com.bookcollector.taxonomy;

import com.bookcollector.common.enums.TargetType;
import com.bookcollector.taxonomy.entity.TaxonomyItem;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 采集目标字典（{@code taxonomy_config}）的 Mongo 访问。
 *
 * <h3>排序为什么是 sort 升序 + _id 兜底</h3>
 * {@code sort} 决定前端下拉框的顺序（对齐原 {@code config.py} 里
 * {@code CATEGORIES} 的书写顺序）。但用户手工新增的条目如果没给 sort，
 * 就会和 seed 数据的 sort 撞车 —— 加上 {@code _id} 兜底保证顺序稳定，
 * 否则同 sort 的两条记录在两次查询里可能换位置，下拉框会「跳」。
 */
@Repository
public class TaxonomyRepository {

    private final MongoTemplate mongoTemplate;

    public TaxonomyRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** 全部字典项，按 type → sort → _id 排序 */
    public List<TaxonomyItem> findAll() {
        Query q = new Query();
        q.with(Sort.by(Sort.Direction.ASC, "type")
                .and(Sort.by(Sort.Direction.ASC, "sort"))
                .and(Sort.by(Sort.Direction.ASC, "_id")));
        return mongoTemplate.find(q, TaxonomyItem.class);
    }

    /**
     * 按类型查。
     *
     * @param enabledOnly true = 只返回 {@code enabled=true} 的（「新建任务」的可选项）
     *                    false/null = 全都要（「字典管理」页）
     */
    public List<TaxonomyItem> findByType(TargetType type, Boolean enabledOnly) {
        Query q = new Query();
        if (type != null) {
            q.addCriteria(Criteria.where("type").is(type.name()));
        }
        if (Boolean.TRUE.equals(enabledOnly)) {
            // 用 is(true) 而不是 ne(false)：seed 早期写入的历史数据可能没有这个字段，
            // ne(false) 会把「字段不存在」也算进来，语义不严
            q.addCriteria(Criteria.where("enabled").is(Boolean.TRUE));
        }
        q.with(Sort.by(Sort.Direction.ASC, "sort").and(Sort.by(Sort.Direction.ASC, "_id")));
        return mongoTemplate.find(q, TaxonomyItem.class);
    }

    public TaxonomyItem findById(String id) {
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        return mongoTemplate.findById(id.trim(), TaxonomyItem.class);
    }

    public TaxonomyItem findByTypeAndCode(TargetType type, String code) {
        if (type == null || code == null || code.trim().isEmpty()) {
            return null;
        }
        return mongoTemplate.findOne(
                Query.query(Criteria.where("type").is(type.name()).and("code").is(code.trim())),
                TaxonomyItem.class);
    }

    public TaxonomyItem save(TaxonomyItem item) {
        return mongoTemplate.save(item);
    }

    public long deleteById(String id) {
        if (id == null || id.trim().isEmpty()) {
            return 0L;
        }
        return mongoTemplate.remove(Query.query(Criteria.where("_id").is(id.trim())),
                TaxonomyItem.class).getDeletedCount();
    }

    public long countByType(TargetType type) {
        if (type == null) {
            return mongoTemplate.count(new Query(), TaxonomyItem.class);
        }
        return mongoTemplate.count(Query.query(Criteria.where("type").is(type.name())),
                TaxonomyItem.class);
    }

    public long countByTypeAndEnabled(TargetType type, boolean enabled) {
        if (type == null) {
            return 0L;
        }
        return mongoTemplate.count(
                Query.query(Criteria.where("type").is(type.name()).and("enabled").is(enabled)),
                TaxonomyItem.class);
    }

    /** 某类型下最大的 sort 值。没有任何条目时返回 0 */
    public int maxSort(TargetType type) {
        Query q = Query.query(Criteria.where("type").is(type.name()));
        q.with(Sort.by(Sort.Direction.DESC, "sort"));
        q.limit(1);
        TaxonomyItem last = mongoTemplate.findOne(q, TaxonomyItem.class);
        if (last == null || last.getSort() == null) {
            return 0;
        }
        return last.getSort();
    }
}
