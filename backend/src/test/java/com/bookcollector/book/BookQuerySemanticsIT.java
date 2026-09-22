package com.bookcollector.book;

import com.bookcollector.book.entity.Book;
import com.bookcollector.common.PageResult;
import com.bookcollector.stats.StatsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S4 的「查询真的筛得动吗」验收 —— 专治<b>静默失效</b>。
 *
 * <h3>这个类为什么必须存在</h3>
 * S1/S2 的测试验的是「解析对不对」（46 个字段值是否一致）和「索引建没建」。
 * 但<b>没有人验过「按评分筛选真的能筛出东西吗」</b>。于是
 * {@code minRating} / {@code maxRating} 指向了一个不存在的字段
 * （{@code newRatingDetail.newRating}），一路静默失效到 S4：
 * 页面不报错、只是筛选结果永远是空列表。
 *
 * <p>这类缺陷的共同特征：<b>查询条件写错字段名不会抛异常</b>。
 * MongoDB 把「字段不存在」当成「这个文档不匹配」，管道和 find 都正常返回空结果。
 * 唯一能照出它的办法是「插已知数据 → 断言筛选结果」，也就是这个类做的事。
 *
 * <h3>为什么不直接用库里已有的 140 条真实数据断言</h3>
 * 真实数据的取值会随采集漂移（S2 就发现过榜单漂移）。这里用
 * <b>自己插的合成数据</b>（推荐值 950 / 500 / 100），断言才有确定性；
 * 真实数据只用来做「分布图不能全是 0」这类软断言。
 *
 * <p>合成数据靠 {@code collectSource = "category:s4q-target"} 与真实数据隔离，
 * 所以即使库里一本真书都没有，这个测试也照样成立。
 */
@SpringBootTest
@DisplayName("S4 · 图书查询语义（评分筛选/排序/分布）")
class BookQuerySemanticsIT {

    private static final String TARGET_TYPE = "CATEGORY";
    private static final String TARGET_ID = "s4q-target";
    private static final String SOURCE_KEY = "category:" + TARGET_ID;

    /** 三本合成书：推荐值 950 / 500 / 100，出版年 2024 / 2015 / 2005 */
    private static final Object[][] FIXTURES = {
            {"s4q-high", 950, "2024-03-01 00:00:00"},
            {"s4q-mid", 500, "2015-06-01 00:00:00"},
            {"s4q-low", 100, "2005-09-01 00:00:00"},
    };

    @Autowired
    private BookRepository repository;

    @Autowired
    private StatsService statsService;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        cleanup();
        List<Book> books = new ArrayList<Book>();
        for (Object[] row : FIXTURES) {
            books.add(Book.builder()
                    .bookId((String) row[0])
                    .title("S4Q " + row[0])
                    .author("S4Q 作者")
                    .newRating((Integer) row[1])
                    .newRatingCount(1000)
                    .publishTime((String) row[2])
                    .firstCollectedAt(new Date())
                    .lastCollectedAt(new Date())
                    .collectSource(new ArrayList<String>(
                            java.util.Collections.singletonList(SOURCE_KEY)))
                    .build());
        }
        mongoTemplate.insertAll(books);
    }

    @AfterEach
    void cleanup() {
        mongoTemplate.remove(Query.query(Criteria.where("bookId").regex("^s4q-")), Book.class);
    }

    // ==================================================================

    @Test
    @DisplayName("评分下限：minRating=900 应筛出 950 那本，而不是空列表")
    void minRatingActuallyFilters() {
        List<String> ids = ids(query(900, null));
        assertTrue(ids.contains("s4q-high"), "★ 900 分以上应包含 950 分那本（这里曾是静默失效点）");
        assertFalse(ids.contains("s4q-mid"), "500 分不应出现在 900 分以上的结果里");
        assertFalse(ids.contains("s4q-low"), "100 分不应出现在 900 分以上的结果里");
    }

    @Test
    @DisplayName("评分上限：maxRating=200 应筛出 100 那本")
    void maxRatingActuallyFilters() {
        List<String> ids = ids(query(null, 200));
        assertTrue(ids.contains("s4q-low"));
        assertFalse(ids.contains("s4q-mid"));
        assertFalse(ids.contains("s4q-high"));
    }

    @Test
    @DisplayName("评分区间：400~600 只应筛出 500 那本")
    void ratingRangeNarrowsToOne() {
        List<String> ids = ids(query(400, 600));
        assertEquals(1, ids.size(), "400~600 应只有一本，实际：" + ids);
        assertEquals("s4q-mid", ids.get(0));
    }

    @Test
    @DisplayName("默认排序：按推荐值降序（950 → 500 → 100）")
    void defaultSortIsByRatingDesc() {
        List<String> ids = ids(query(null, null));
        assertEquals(3, ids.size(), "应能筛出全部 3 本合成书，实际：" + ids);
        assertEquals("s4q-high", ids.get(0), "★ 默认排序应是推荐值降序（曾指向不存在的字段，排序静默失效）");
        assertEquals("s4q-mid", ids.get(1));
        assertEquals("s4q-low", ids.get(2));

        // 升序也要能用
        BookQuery asc = query(null, null);
        asc.setAsc(true);
        assertEquals("s4q-low", ids(asc).get(0), "升序时最低分应在最前");
    }

    @Test
    @DisplayName("出版时间区间：字符串比较也能正确收敛（2020 起 → 只剩 2024 那本）")
    void publishTimeRangeWorks() {
        BookQuery q = query(null, null);
        q.setMinPublishTime("2020");
        List<String> ids = ids(q);
        assertEquals(1, ids.size(), "2020 年之后应只剩一本，实际：" + ids);
        assertEquals("s4q-high", ids.get(0));

        // 只传年份的「上限」要能被补成「该年最后一天」，否则 2024 那本会被漏掉
        BookQuery upper = query(null, null);
        upper.setMaxPublishTime("2015");
        List<String> upperIds = ids(upper);
        assertEquals(2, upperIds.size(),
                "「2015 及以前」应含 2015 和 2005 两本（年份上限必须补齐到 12-31），实际：" + upperIds);
    }

    @Test
    @DisplayName("评分分布：10 个桶的计数总和 == 有推荐值的图书数（不能全 0）")
    void ratingDistributionIsNotEmpty() {
        long withRating = mongoTemplate.count(
                Query.query(Criteria.where("newRating").gte(0)), Book.class);
        assertTrue(withRating >= 3, "库里有推荐值的书应至少有 3 本（合成数据），实际 " + withRating);

        List<Map<String, Object>> buckets = asList(statsService.charts().get("ratingDistribution"));
        assertEquals(10, buckets.size(), "评分分布应固定 10 个桶");

        long sum = 0L;
        for (Map<String, Object> bucket : buckets) {
            sum += ((Number) bucket.get("count")).longValue();
        }
        assertEquals(withRating, sum,
                "★ 各桶计数之和必须等于「有推荐值的图书数」（曾经因为字段路径写错而全为 0）");

        // 900-1000 这一桶必须含我们插的 950 分那本
        Map<String, Object> lastBucket = buckets.get(9);
        assertEquals("900-1000", lastBucket.get("label"));
        assertTrue(((Number) lastBucket.get("count")).longValue() >= 1L,
                "900-1000 桶应至少含 950 分那本");
    }

    @Test
    @DisplayName("评分分布的桶边界：1000 分要落在最后一桶，而不是掉出去")
    void ratingBucketBoundary() {
        mongoTemplate.insert(Book.builder()
                .bookId("s4q-max")
                .title("S4Q 满分")
                .newRating(1000)
                .collectSource(new ArrayList<String>(
                        java.util.Collections.singletonList(SOURCE_KEY)))
                .build());

        List<Map<String, Object>> buckets = asList(statsService.charts().get("ratingDistribution"));
        Map<String, Object> lastBucket = buckets.get(9);
        // 950 + 1000 两本都应落在 900-1000 桶
        assertTrue(((Number) lastBucket.get("count")).longValue() >= 2L,
                "1000 分必须落在最后一桶（$floor(1000/100)=10 会掉出去，这就是不用 $bucket 的原因）");
    }

    // ==================================================================

    private BookQuery query(Integer minRating, Integer maxRating) {
        BookQuery q = new BookQuery();
        q.setTargetType(TARGET_TYPE);
        q.setTargetId(TARGET_ID);
        q.setMinRating(minRating);
        q.setMaxRating(maxRating);
        q.setSize(100);
        return q;
    }

    private List<String> ids(BookQuery q) {
        PageResult<Book> page = repository.pageQuery(q);
        List<String> ids = new ArrayList<String>();
        for (Book book : page.getList()) {
            ids.add(book.getBookId());
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asList(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
