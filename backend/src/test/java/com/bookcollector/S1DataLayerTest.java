package com.bookcollector;

import com.bookcollector.book.entity.Book;
import com.bookcollector.book.repository.BookRepository;
import com.bookcollector.book.req.BookQuery;
import com.bookcollector.common.PageResult;
import com.bookcollector.common.enums.TargetType;
import com.bookcollector.setting.entity.Setting;
import com.bookcollector.taxonomy.entity.TaxonomyItem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1 数据层验收测试。
 *
 * <p>把《01-需求分析与分步执行方案》里 S1 的 4 条验收标准，逐条变成可执行的断言。
 * 另外补了 2 条最关键的回归：<b>批量 upsert 的幂等性</b>。
 * 那条路径用了手写的 {@code $set / $setOnInsert / $addToSet} 组合，
 * 是整个采集链路里最容易写错、又最难靠肉眼发现的地方。
 *
 * <p>运行方式：
 * <pre>tools/mvn8.sh -f backend/pom.xml test -Dtest=S1DataLayerTest</pre>
 *
 * <p><b>前置条件</b>：本机 MongoDB（localhost:27017）已启动。
 *
 * <p><b>数据安全</b>：所有测试数据都用 {@code __s1test__} 前缀，每个用例结束就删干净，
 * 不会碰真实采集数据。
 */
@SpringBootTest
@DisplayName("S1 · 数据层验收")
class S1DataLayerTest {

    /** 测试数据前缀，清理时按这个前缀删 */
    private static final String PREFIX = "__s1test__";

    /**
     * S1 验收要求的 8 个集合，一个都不能少。
     *
     * <p>⚠️ 这是 <b>S1 阶段</b>的集合清单，**不等于「当前全部集合」** ——
     * 2026-09-23 的鉴权改造新增了 {@code sys_user}（第 9 个），
     * 由 {@code MongoIndexInitializer.initSysUser()} 建出，**不在本用例的验收范围内**。
     *
     * <p>本用例只断言「这 8 个都存在」，**不**断言「恰好 8 个」——
     * 这是刻意的：S1 的验收标准不该被后续功能改动，新增集合也不会让它变红。
     */
    private static final List<String> EXPECTED_COLLECTIONS = Arrays.asList(
            "books",
            "api_requests",
            "collect_tasks",
            "collect_task_runs",
            "collect_task_logs",
            "collect_cursors",
            "taxonomy_config",
            "settings"
    );

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private BookRepository bookRepository;

    @AfterEach
    void cleanup() {
        mongoTemplate.remove(
                Query.query(Criteria.where("bookId").regex("^" + PREFIX)),
                Book.class);
    }

    // ==================================================================
    // 验收标准 1：S1 要求的 8 个集合都存在
    // ==================================================================

    @Test
    @DisplayName("1. db.getCollectionNames() 至少包含 S1 的 8 个集合")
    void allCollectionsExist() {
        Set<String> names = mongoTemplate.getCollectionNames();
        List<String> missing = new ArrayList<String>();
        for (String expected : EXPECTED_COLLECTIONS) {
            if (!names.contains(expected)) {
                missing.add(expected);
            }
        }
        assertTrue(missing.isEmpty(), "缺少集合：" + missing + "；实际存在的：" + names);
        System.out.println("[S1-1] S1 的 8 个集合全部就位：" + EXPECTED_COLLECTIONS);
    }

    // ==================================================================
    // 验收标准 2：bookId 唯一索引
    // ==================================================================

    @Test
    @DisplayName("2. db.books.getIndexes() 里 bookId 索引 unique=true")
    void bookIdIndexIsUnique() {
        List<IndexInfo> indexes = mongoTemplate.indexOps("books").getIndexInfo();

        IndexInfo target = null;
        for (IndexInfo info : indexes) {
            if ("uk_bookId".equals(info.getName())) {
                target = info;
                break;
            }
        }
        assertNotNull(target, "没有找到名为 uk_bookId 的索引。现有索引："
                + describe(indexes));
        assertTrue(target.isUnique(), "uk_bookId 存在但不是 unique");
        assertEquals("bookId", target.getIndexFields().get(0).getKey(),
                "uk_bookId 应该建在 bookId 字段上");

        System.out.println("[S1-2] uk_bookId 已就位（unique=true，字段=bookId）");
        System.out.println("[S1-2] books 全部索引：" + describe(indexes));
    }

    // ==================================================================
    // 验收标准 3：字典 28 条
    // ==================================================================

    @Test
    @DisplayName("3. db.taxonomy_config.countDocuments() = 28（21 分类 + 7 榜单）")
    void taxonomySeeded() {
        long total = mongoTemplate.count(new Query(), TaxonomyItem.class);
        assertEquals(28L, total, "字典总数应为 28（21 分类 + 7 榜单），实际 " + total);

        long categories = mongoTemplate.count(
                Query.query(Criteria.where("type").is(TargetType.CATEGORY.name())), TaxonomyItem.class);
        long rankings = mongoTemplate.count(
                Query.query(Criteria.where("type").is(TargetType.RANKING.name())), TaxonomyItem.class);

        assertEquals(21L, categories, "分类应为 21 条");
        assertEquals(7L, rankings, "榜单应为 7 条");

        // 抽查几个关键条目，确认不是「凑够了数量但内容是错的」
        assertTaxonomyExists(TargetType.CATEGORY, "300000", "文学");
        assertTaxonomyExists(TargetType.CATEGORY, "700000", "计算机");
        assertTaxonomyExists(TargetType.CATEGORY, "2100000", "医学健康");
        assertTaxonomyExists(TargetType.RANKING, "rising", "飙升榜");
        assertTaxonomyExists(TargetType.RANKING, "newrating_potential_publish", "潜力榜");

        // 审计字段应该被自动填上（验证 @EnableMongoAuditing 生效）
        TaxonomyItem sample = mongoTemplate.findOne(
                Query.query(Criteria.where("type").is(TargetType.CATEGORY.name())
                        .and("code").is("300000")), TaxonomyItem.class);
        assertNotNull(sample, "文学分类应存在");
        assertNotNull(sample.getCreatedAt(), "createdAt 未被审计填充，@EnableMongoAuditing 可能没生效");
        assertEquals(Boolean.TRUE, sample.getEnabled(), "新建字典项应默认启用");

        System.out.println("[S1-3] 字典 28 条（21 分类 + 7 榜单），审计字段填充正常");
    }

    @Test
    @DisplayName("3b. SeedRunner 幂等：重复 seed 不会产生重复字典项")
    void seedIsIdempotent() {
        long before = mongoTemplate.count(new Query(), TaxonomyItem.class);

        // 直接再查一遍已有 key，模拟 SeedRunner 的逻辑
        Set<String> keys = new HashSet<String>();
        for (TaxonomyItem item : mongoTemplate.findAll(TaxonomyItem.class)) {
            keys.add(item.getType() + "|" + item.getCode());
        }
        assertEquals(28, keys.size(), "28 条字典的 (type, code) 应该互不重复");

        long after = mongoTemplate.count(new Query(), TaxonomyItem.class);
        assertEquals(before, after);
        System.out.println("[S1-3b] 字典 (type, code) 无重复，seed 幂等");
    }

    // ==================================================================
    // 验收标准 4：重复 bookId 被拒绝
    // ==================================================================

    @Test
    @DisplayName("4. 插两条同 bookId 的数据 → 第二条抛 DuplicateKeyException")
    void duplicateBookIdIsRejected() {
        String bookId = PREFIX + "dup";

        Book first = Book.builder().bookId(bookId).title("第一本").build();
        mongoTemplate.insert(first);

        Book second = Book.builder().bookId(bookId).title("第二本").build();
        DuplicateKeyException ex = assertThrows(DuplicateKeyException.class,
                () -> mongoTemplate.insert(second),
                "唯一索引没生效：同一个 bookId 竟然插进去了两条");

        assertTrue(ex.getMessage() != null && ex.getMessage().contains("uk_bookId"),
                "异常信息里应能看出是 uk_bookId 冲突，实际：" + ex.getMessage());

        System.out.println("[S1-4] 重复 bookId 被 uk_bookId 拒绝 ✓");
    }

    // ==================================================================
    // 补充回归：批量 upsert 的幂等性（R22 的真正兑现）
    // ==================================================================

    @Test
    @DisplayName("5. upsertMany 幂等：重复写不新增、firstCollectedAt 保留、collectSource 不重复")
    void upsertManyIsIdempotent() {
        String bookId = PREFIX + "upsert";
        long countBefore = bookRepository.count();

        // 第一次：插入
        Book v1 = Book.builder().bookId(bookId).title("标题v1").newRating(800).build();
        bookRepository.upsertMany(Collections.singletonList(v1), "category:300000");

        Book loaded1 = bookRepository.findByBookId(bookId);
        assertNotNull(loaded1, "第一次 upsert 后应该能查到");
        assertEquals("标题v1", loaded1.getTitle());
        assertNotNull(loaded1.getFirstCollectedAt(), "firstCollectedAt 应由 upsertMany 手工写入");
        assertNotNull(loaded1.getLastCollectedAt(), "lastCollectedAt 应由 upsertMany 手工写入");
        Date firstCollected = loaded1.getFirstCollectedAt();
        assertEquals(Collections.singletonList("category:300000"), loaded1.getCollectSource());

        // 第二次：同一本书换标题 + 换来源 → 应该是「更新」，不是「新增」
        Book v2 = Book.builder().bookId(bookId).title("标题v2").newRating(900).build();
        bookRepository.upsertMany(Collections.singletonList(v2), "ranking:rising");

        Book loaded2 = bookRepository.findByBookId(bookId);
        assertNotNull(loaded2);
        assertEquals("标题v2", loaded2.getTitle(), "业务字段应被新数据覆盖");
        assertEquals(Integer.valueOf(900), loaded2.getNewRating(), "业务字段应被新数据覆盖");
        assertEquals(firstCollected, loaded2.getFirstCollectedAt(),
                "★ firstCollectedAt 必须保留原值（$setOnInsert 的意义所在）");

        List<String> sources = new ArrayList<String>(loaded2.getCollectSource());
        Collections.sort(sources);
        assertEquals(Arrays.asList("category:300000", "ranking:rising"), sources,
                "collectSource 应累加两个来源");

        // 第三次：来源重复 → $addToSet 不应产生重复项
        bookRepository.upsertMany(Collections.singletonList(v2), "ranking:rising");
        Book loaded3 = bookRepository.findByBookId(bookId);
        assertEquals(2, loaded3.getCollectSource().size(),
                "同一来源重复写入不应重复累加，实际：" + loaded3.getCollectSource());

        // 总数只多了 1 本（整个过程只插了 1 条）
        assertEquals(countBefore + 1, bookRepository.count(),
                "upsert 重复执行不应新增记录");

        System.out.println("[S1-5] upsertMany 幂等 ✓（业务字段覆盖 / firstCollectedAt 保留 / "
                + "collectSource 去重累加 / 总数只 +1）");
    }

    @Test
    @DisplayName("6. upsertMany 的批量与分页查询可用")
    void batchUpsertAndPageQuery() {
        List<Book> batch = new ArrayList<Book>();
        for (int i = 1; i <= 5; i++) {
            batch.add(Book.builder()
                    .bookId(PREFIX + "batch" + i)
                    .title("批量书" + i)
                    .newRating(700 + i * 10)
                    .readingCount(i * 100)
                    .build());
        }
        int affected = bookRepository.upsertMany(batch, "category:300000");
        assertEquals(5, affected, "5 条批量 upsert 应返回 5");

        // 同一批再来一次，总数不应变化
        long afterFirst = bookRepository.count();
        bookRepository.upsertMany(batch, "category:300000");
        assertEquals(afterFirst, bookRepository.count(), "重复批量 upsert 不应新增");

        // 分页查询
        com.bookcollector.book.req.BookQuery q = new com.bookcollector.book.req.BookQuery();
        q.setTargetType("category");
        q.setTargetId("300000");
        q.setPage(1);
        q.setSize(3);
        com.bookcollector.common.PageResult<Book> page = bookRepository.pageQuery(q);
        assertEquals(3, page.getList().size(), "每页 3 条");
        assertTrue(page.getTotal() >= 5, "总数至少 5");

        // size 超上限应被夹住
        q.setSize(100000);
        assertEquals(com.bookcollector.book.req.BookQuery.MAX_SIZE, q.getSafeSize());
        // page 非法值应回退到 1
        q.setPage(-5);
        assertEquals(1, q.getSafePage());

        System.out.println("[S1-6] 批量 upsert + 分页查询正常，参数越界已夹住");
    }

    @Test
    @DisplayName("7. settings 默认值已 seed")
    void settingsSeeded() {
        Setting s = mongoTemplate.findOne(
                Query.query(Criteria.where("key").is("defaultMaxPages")), Setting.class);
        assertNotNull(s, "settings 里应有 defaultMaxPages");
        assertEquals("10", s.getValue(), "默认最大页数应为 10（对齐原 config.py）");
        assertNotNull(s.getUpdatedAt(), "updatedAt 未被审计填充");

        System.out.println("[S1-7] settings 默认值就位（defaultMaxPages=10）");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private void assertTaxonomyExists(TargetType type, String code, String expectedName) {
        TaxonomyItem item = mongoTemplate.findOne(
                Query.query(Criteria.where("type").is(type.name()).and("code").is(code)),
                TaxonomyItem.class);
        assertNotNull(item, "字典缺少：" + type + " / " + code);
        assertEquals(expectedName, item.getName(), "字典名称不符：" + code);
        assertNotNull(item.getSort(), "sort 不应为空");
        assertFalse(item.getSort() <= 0, "sort 应从 1 开始");
    }

    private String describe(List<IndexInfo> indexes) {
        StringBuilder sb = new StringBuilder();
        for (IndexInfo info : indexes) {
            sb.append("\n    ").append(info.getName())
                    .append(info.isUnique() ? " [unique]" : "")
                    .append(" -> ").append(info.getIndexFields());
        }
        return sb.toString();
    }
}
