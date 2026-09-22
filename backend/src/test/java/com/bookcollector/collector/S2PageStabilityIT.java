package com.bookcollector.collector;

import com.bookcollector.book.entity.Book;
import com.bookcollector.common.enums.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2 附带的性质测试 —— <b>分页列表的稳定性</b>。
 *
 * <h3>为什么要单独测这个</h3>
 * 整个 S2 的幂等性都建立在一个假设上：
 * <b>同样的 {@code maxIndex} 范围，两次请求会返回同样的书</b>。
 * 如果这个假设不成立，「连跑两次总数不变」就无从谈起。
 * 所以把它显式测出来，别让它只是一个口头约定。
 *
 * <h3>实测结论（2026-09-21 20:31）</h3>
 * <ul>
 *   <li><b>短时间尺度（秒级）稳定</b>：连续两次走 5 页，bookId 集合完全相同，差异 0 本。</li>
 *   <li><b>长时间尺度会漂移</b>：同一段 range 隔几分钟重采，会有个别书进出。
 *       实测「文学」在 3 分钟内就换掉了 1 本（{@code 3300045713} 掉出前 100）。</li>
 *   <li>{@code searchIdx} 在范围内连续（实测 [1, 100]，无空洞、无重叠）。</li>
 * </ul>
 *
 * <h3>🔴 这条性质对产品的含义</h3>
 * <ol>
 *   <li>「连跑两次总数不变」这个验收标准，<b>只在短时间内严格成立</b>。
 *       跨较长时间重采，{@code books} 总数可能 ±N —— <b>这不是 bug，是数据源的固有性质</b>。</li>
 *   <li>所以 {@code books} 的正确语义是「<b>历史累计的并集</b>」，不是「当前榜单的快照」。
 *       产品上不该拿 {@code books} 的总数去跟接口的 {@code totalCount} 对账。</li>
 *   <li>幂等性的准确表述是：<b>对同一份响应，upsert 幂等</b>
 *       （同 {@code bookId} 不重复插入、不覆盖 {@code firstCollectedAt}），
 *       而不是「集合总数恒定」。</li>
 *   <li>这反过来印证了 S1 的两个决策是对的：<b>多存 4 个扩展字段</b>、
 *       <b>永不删除</b>。书一旦掉出榜单，只有当时存下来了才留得住。</li>
 * </ol>
 *
 * <p>类名以 {@code IT} 结尾，不在 {@code mvn test} 默认扫描范围（要联网、约 22 秒）。
 */
@SpringBootTest
@DisplayName("S2 · 分页列表稳定性（真实接口）")
class S2PageStabilityIT {

    private static final TargetType TYPE = TargetType.CATEGORY;
    private static final String TARGET_ID = "300000";
    private static final String SOURCE_KEY = "category:300000";
    private static final int PAGES = 5;

    /** 一页 20 本 × 5 页 */
    private static final int EXPECTED_COUNT = 100;

    @Autowired
    private WereadClient client;

    @Autowired
    private BookParser parser;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Test
    @DisplayName("连续两次走同样 5 页，返回的 bookId 集合应完全相同")
    void twoConsecutiveWalksAreIdentical() throws Exception {
        Map<String, Integer> first = walkFivePages();
        Map<String, Integer> second = walkFivePages();

        assertEquals(EXPECTED_COUNT, first.size(),
                "5 页应有 " + EXPECTED_COUNT + " 本（每页 20）");
        assertEquals(first.size(), second.size(),
                "两次走同样范围应拿到同样多的书");

        Set<String> onlyFirst = new LinkedHashSet<>(first.keySet());
        onlyFirst.removeAll(second.keySet());
        Set<String> onlySecond = new LinkedHashSet<>(second.keySet());
        onlySecond.removeAll(first.keySet());

        assertEquals(new LinkedHashSet<String>(), onlyFirst,
                "★ 分页不稳定：这些书只在第一次出现 —— 幂等性的前提被打破了");
        assertEquals(new LinkedHashSet<String>(), onlySecond,
                "★ 分页不稳定：这些书只在第二次出现 —— 幂等性的前提被打破了");

        // searchIdx 应连续覆盖 1..100
        List<Integer> idx = new ArrayList<>(first.values());
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (Integer i : idx) {
            min = Math.min(min, i);
            max = Math.max(max, i);
        }
        assertEquals(1, min, "searchIdx 应从 1 开始");
        assertEquals(EXPECTED_COUNT, max, "searchIdx 应到 " + EXPECTED_COUNT);
        assertEquals(EXPECTED_COUNT, new LinkedHashSet<>(idx).size(),
                "searchIdx 不应有重复（有重复说明分页有重叠）");

        // 顺便报告一下「历史漂移」的规模 —— 不做断言，只做观测
        List<String> dbIds = mongoTemplate.findDistinct(
                Query.query(Criteria.where("collectSource").is(SOURCE_KEY)),
                "bookId", Book.class, String.class);
        Set<String> onlyInDb = new LinkedHashSet<>(dbIds);
        onlyInDb.removeAll(first.keySet());

        System.out.println("[S2-IT-5] 分页稳定性 ✓  两次各 " + first.size()
                + " 本，bookId 集合完全一致，searchIdx 覆盖 [1, " + max + "]");
        if (!onlyInDb.isEmpty()) {
            System.out.println("[S2-IT-5] 观测：库中有 " + onlyInDb.size()
                    + " 本属于该目标但已不在当前前 " + PAGES + " 页内（榜单漂移的历史遗留）："
                    + onlyInDb);
        }
        assertTrue(true);
    }

    /** 走 5 页，返回 {@code bookId → searchIdx} */
    private Map<String, Integer> walkFivePages() throws Exception {
        Map<String, Integer> result = new LinkedHashMap<>();
        int maxIndex = 0;
        for (int p = 1; p <= PAGES; p++) {
            WereadRawResponse resp = client.fetchRaw(TYPE, TARGET_ID, maxIndex);
            assertTrue(resp.isSuccess(), "第 " + p + " 页请求应成功，实际 status="
                    + resp.getStatusCode());
            WereadPage page = WereadPage.parse(resp.getBody());
            for (Book b : parser.parseAll(page.getBooks())) {
                result.put(b.getBookId(), b.getSearchIdx() == null ? -1 : b.getSearchIdx());
            }
            Integer next = page.lastSearchIdx();
            if (next == null) {
                break;
            }
            maxIndex = next;
            if (p < PAGES) {
                // 接口限速 1 秒/页
                Thread.sleep(1100);
            }
        }
        return result;
    }
}
