package com.bookcollector.util;

import com.bookcollector.common.PageResult;

import cn.hutool.core.bean.BeanUtil;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.ArrayList;
import java.util.List;

/**
 * 分页查询样板收敛。
 *
 * <h3>为什么要有这个类</h3>
 * 本项目的分页查询都是同一个套路：<b>先 count → 为 0 直接返回空页 → 否则加排序 + skip/limit → find</b>。
 * 三处（{@code book} / {@code task} / {@code audit}）此前各写一遍，
 * 任何一处要改口径（比如以后换游标分页）都得改三遍，还容易改漏。
 *
 * <h3>🔴 三条被刻意保留的行为（不是随手写的）</h3>
 * <ol>
 *   <li><b>{@code count == 0} 短路</b>：为 0 时不发 find，省一次往返。
 *       这是本项目原有行为，与常见参考实现不同。</li>
 *   <li><b>调用方必须显式给排序</b>：本项目的业务字段是
 *       {@code lastCollectedAt} / {@code createdAt} / {@code startedAt}，
 *       <b>没有</b>通用的 {@code createTime}。所以这里<b>不提供默认排序字段</b> ——
 *       单字段重载传空会直接抛异常，而不是静默按一个不存在的字段排
 *       （那会退化成自然序，且不报错，最难发现）。</li>
 *   <li><b>两级不同方向的排序要能表达</b>：「主排序字段 + {@code _id ASC} 兜底」是
 *       保证同值记录翻页不重不漏的关键，所以必须支持显式 {@link Sort}。</li>
 * </ol>
 *
 * <h3>参数归一化口径（全项目唯一来源）</h3>
 * {@code page < 1 → 1}、{@code size < 1 → 20}、{@code size > 200 → 200}。
 * 调用方通常已经归一化过（如 {@code BookQuery#getSafePage()}），这里的兜底是<b>幂等</b>的。
 *
 * <h3>⚠️ count 必须在加 skip/limit 之前</h3>
 * 先 count 再 {@code query.with(PageRequest...)}。反过来 count 的语义会变成
 * 「本页条数」而不是总数。这个方法内部已经按正确顺序写了，调用方不用关心。
 */
public final class PageQueryUtil {

    /** 默认页码 */
    public static final int DEFAULT_PAGE = 1;

    /** 默认每页条数 */
    public static final int DEFAULT_SIZE = 20;

    /** 每页条数上限，防止前端传 {@code size=100000} 把服务打爆 */
    public static final int MAX_SIZE = 200;

    private PageQueryUtil() {
        throw new AssertionError("PageQueryUtil 工具类不允许实例化");
    }

    /**
     * 单字段排序分页。
     *
     * @param sortField Mongo 字段路径，<b>必须显式给出</b>（本项目没有通用的 {@code createTime}）
     */
    public static <R> PageResult<R> getPageResult(MongoTemplate mongoTemplate,
                                                  Integer pageNum, Integer pageSize,
                                                  Criteria criteria, Class<R> clazz,
                                                  Sort.Direction direction, String sortField) {
        if (sortField == null || sortField.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "sortField 不能为空 —— 本项目没有通用的 createTime 字段，必须显式指定排序字段");
        }
        return getPageResult(mongoTemplate, pageNum, pageSize, criteria, clazz,
                Sort.by(direction, sortField.trim()));
    }

    /** 显式 Sort 分页（用于「主排序 + {@code _id} 兜底」这类多字段 / 多方向排序） */
    public static <R> PageResult<R> getPageResult(MongoTemplate mongoTemplate,
                                                  Integer pageNum, Integer pageSize,
                                                  Criteria criteria, Class<R> clazz,
                                                  Sort sort) {
        int page = normalizePage(pageNum);
        int size = normalizeSize(pageSize);

        Query query = new Query(criteria == null ? new Criteria() : criteria);
        // 先 count（此时还没加 skip/limit），为 0 直接返回空页，省一次 find
        long total = mongoTemplate.count(query, clazz);
        if (total == 0L) {
            return PageResult.empty(page, size);
        }

        if (sort != null) {
            query.with(sort);
        }
        query.with(PageRequest.of(page - 1, size));
        List<R> list = mongoTemplate.find(query, clazz);
        return PageResult.of(list, total, page, size);
    }

    /**
     * 分页结果转换（Entity → Resp）。
     *
     * <p>元素转换用 Hutool 的 {@link BeanUtil#copyProperties(Object, Class)}；
     * <b>信封字段（total / page / size）直接 setter 赋值，不用 {@code BeanUtil} 整体拷贝</b>
     * —— {@code PageResult} 有派生的 {@code getTotalPages()}，整体拷贝容易出现意料外的属性匹配。
     */
    public static <S, T> PageResult<T> convertPageResult(PageResult<S> source, Class<T> targetClass) {
        if (source == null) {
            return null;
        }
        List<T> list = new ArrayList<T>();
        if (source.getList() != null) {
            for (S item : source.getList()) {
                list.add(item == null ? null : BeanUtil.copyProperties(item, targetClass));
            }
        }
        return PageResult.of(list, source.getTotal(), source.getPage(), source.getSize());
    }

    /** {@code page < 1 → 1} */
    public static int normalizePage(Integer pageNum) {
        return (pageNum == null || pageNum < DEFAULT_PAGE) ? DEFAULT_PAGE : pageNum;
    }

    /** {@code size < 1 → 20}、{@code size > 200 → 200} */
    public static int normalizeSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(pageSize, MAX_SIZE);
    }
}
