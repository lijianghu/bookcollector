package com.bookcollector.collector;

import com.bookcollector.book.entity.Book;
import com.bookcollector.collector.dto.WereadPage;
import com.bookcollector.collector.dto.WereadRawResponse;
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
 * <h3>🔴 断言的是「机制」，不是「内容」</h3>
 * 分页是 <b>{@code maxIndex}（searchIdx 偏移）式</b>的 —— 第 N 页用「上一页最后一条的
 * {@code searchIdx}」当游标。而榜单是一个<b>可变列表</b>：两次请求之间（实测间隔约 1 秒）
 * 若有书进出，位置会整体平移，边界处就会<b>重叠</b>（同一本出现在相邻两页）或
 * <b>留空洞</b>（一本被跳过）。<b>这是偏移式分页在可变列表上的固有性质，客户端消除不了。</b>
 *
 * <p>所以「走 5 页恰好拿到 100 个不同 {@code bookId}」<b>是一条不成立的断言</b> ——
 * 它等价于要求上游榜单在两次请求之间不变。2026-09-23 实测就因此变红：
 * 5 页 × 20 条原始节点、<b>0 条解析失败</b>，去重后只有 99 本。
 *
 * <p>本测试改为断言<b>与内容无关的分页不变量</b>（每页 20 条 / 20 本、
 * {@code searchIdx} 全程严格递增、从 1 开始、覆盖到 ~100），
 * 并给「榜单漂移」留一个显式的容忍度 {@link #MAX_DRIFT}。
 *
 * <h3>实测结论</h3>
 * <ul>
 *   <li><b>分页机制稳定</b>：{@code searchIdx} 在范围内连续（实测 [1, 100]，无空洞、无重叠、
 *       严格递增）。这是本测试真正要守住的东西。</li>
 *   <li><b>榜单内容会漂移</b>：同一段 range 隔几分钟重采，会有个别书进出。
 *       实测「文学」在 3 分钟内就换掉了 1 本（{@code 3300045713} 掉出前 100）。</li>
 *   <li><b>漂移的痕迹会留在库里</b>：{@code category:300000} 实测 {@code searchIdx=60}
 *       缺失、而 {@code searchIdx=61} 上有两本不同的书 —— 正是「榜单平移 +
 *       upsert 是并集、永不删除」的产物。</li>
 * </ul>
 *
 * <h3>🔴 这条性质对产品的含义</h3>
 * <ol>
 *   <li>「连跑两次总数不变」这个验收标准，<b>只在短时间内近似成立</b>。
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
 * <h3>排查「少了一条」时先看这两个数字</h3>
 * <table border="1">
 *   <tr><th>查什么</th><th>怎么查</th><th>结论</th></tr>
 *   <tr><td>上游有没有少给</td><td>{@code api_requests.resultCount}
 *       （= {@link WereadPage#size()}，<b>原始节点数</b>）</td>
 *       <td>每页都是 20 → 上游正常</td></tr>
 *   <tr><td>解析器有没有丢书</td><td>日志里有没有「解析第 N 条（searchIdx=…）失败」的 WARN</td>
 *       <td>0 条 → 解析正常</td></tr>
 *   <tr><td>二者都正常却少 1</td><td>——</td><td><b>必定是跨页重复</b>，不是 bug</td></tr>
 * </table>
 *
 * <p>类名以 {@code IT} 结尾，不在 {@code mvn test} 默认扫描范围（要联网、约 20 秒）。
 */
@SpringBootTest
@DisplayName("S2 · 分页列表稳定性（真实接口）")
class S2PageStabilityIT {

    private static final TargetType TYPE = TargetType.CATEGORY;
    private static final String TARGET_ID = "300000";
    private static final String SOURCE_KEY = "category:300000";
    private static final int PAGES = 5;

    /** 一页 20 本 */
    private static final int PAGE_SIZE = 20;

    /** 一页 20 本 × 5 页 */
    private static final int EXPECTED_COUNT = PAGES * PAGE_SIZE;

    /**
     * 榜单漂移容忍度 —— 两次走之间允许的 {@code bookId} 对称差上限。
     *
     * <p>取值理由：正常漂移实测是 1~2 本；给到 5 留足余量。
     * 但也不能太大 —— 若分页游标失效（比如始终请求 {@code maxIndex=0}），
     * 对称差会达到几十本，这条断言必须能把它抓住。
     */
    private static final int MAX_DRIFT = 5;

    @Autowired
    private WereadClient client;

    @Autowired
    private BookParser parser;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Test
    @DisplayName("连续两次走同样 5 页：分页机制稳定，榜单漂移在容忍度内")
    void twoConsecutiveWalksAreStable() throws Exception {
        Walk first = walkFivePages();
        Walk second = walkFivePages();

        // ---------- ① 上游契约 & 解析器：每页 20 条原始节点、20 本解析成功 ----------
        // 这一组与榜单内容无关，任何漂移都不该让它红 ——
        // 它抓的是「上游少给数据」和「解析器丢书」这两类真正的故障。
        assertEquals(PAGES, first.rawCounts.size(),
                "应走满 " + PAGES + " 页（不足说明接口提前返回了空页）");
        for (int p = 0; p < PAGES; p++) {
            assertEquals(PAGE_SIZE, first.rawCounts.get(p).intValue(),
                    "第 " + (p + 1) + " 页应返回 " + PAGE_SIZE + " 条原始节点"
                            + "（少了 = 上游少给，不是分页问题）");
            assertEquals(PAGE_SIZE, first.parsedCounts.get(p).intValue(),
                    "第 " + (p + 1) + " 页应解析出 " + PAGE_SIZE + " 本"
                            + "（少了 = 解析器丢书）");
        }

        // ---------- ② 分页不变量：searchIdx 全程严格递增 ----------
        // 这是「分页机制」本身的性质：第 N 页的 maxIndex 就是第 N-1 页的末条 searchIdx，
        // 所以下一页返回的每一条都必须大于上一页的末条。榜单漂移也破坏不了它。
        assertTrue(first.isStrictlyIncreasing(),
                "searchIdx 应全程严格递增 —— 不递增说明分页游标回退/重叠，是分页机制的 bug");
        assertTrue(second.isStrictlyIncreasing(),
                "searchIdx 应全程严格递增 —— 不递增说明分页游标回退/重叠，是分页机制的 bug");
        assertEquals(1, first.minIdx(), "searchIdx 应从 1 开始");
        assertEquals(1, second.minIdx(), "searchIdx 应从 1 开始");
        assertEquals(first.idxOrder.size(), first.distinctIdxCount(),
                "同一次走里不应有两个条目共用同一个 searchIdx");

        // ---------- ③ 覆盖范围：5 页应走到 ~100 ----------
        assertTrue(Math.abs(first.maxIdx() - EXPECTED_COUNT) <= MAX_DRIFT,
                "5 页应走到 searchIdx ≈ " + EXPECTED_COUNT + "，实际 " + first.maxIdx());
        assertTrue(Math.abs(second.maxIdx() - EXPECTED_COUNT) <= MAX_DRIFT,
                "5 页应走到 searchIdx ≈ " + EXPECTED_COUNT + "，实际 " + second.maxIdx());

        // ---------- ④ 榜单漂移：两次走的差异应很小（数据源性质，不是 bug）----------
        Set<String> onlyFirst = new LinkedHashSet<String>(first.byId.keySet());
        onlyFirst.removeAll(second.byId.keySet());
        Set<String> onlySecond = new LinkedHashSet<String>(second.byId.keySet());
        onlySecond.removeAll(first.byId.keySet());
        int drift = onlyFirst.size() + onlySecond.size();
        assertTrue(drift <= MAX_DRIFT,
                "两次走的对称差 " + drift + " 超过容忍度 " + MAX_DRIFT
                        + " —— 差异这么大就不像榜单漂移了，检查分页是否失效。"
                        + " 只在第一次出现：" + onlyFirst
                        + "；只在第二次出现：" + onlySecond);

        // ---------- 观测输出（不断言，只留证据）----------
        List<String> dbIds = mongoTemplate.findDistinct(
                Query.query(Criteria.where("collectSource").is(SOURCE_KEY)),
                "bookId", Book.class, String.class);
        Set<String> onlyInDb = new LinkedHashSet<String>(dbIds);
        onlyInDb.removeAll(first.byId.keySet());

        System.out.println("[S2-IT-5] 分页机制 ✓  两次各 " + first.idxOrder.size()
                + " 条，searchIdx 全程严格递增，覆盖 [1, " + first.maxIdx() + "]");
        System.out.println("[S2-IT-5] 去重后 bookId：第一次 " + first.byId.size()
                + " 本，第二次 " + second.byId.size() + " 本，对称差 " + drift
                + "（榜单在两次之间漂移，属数据源固有性质，非缺陷）");
        if (!onlyInDb.isEmpty()) {
            System.out.println("[S2-IT-5] 观测：库中有 " + onlyInDb.size()
                    + " 本属于该目标但已不在当前前 " + PAGES + " 页内（榜单漂移的历史遗留）："
                    + onlyInDb);
        }
    }

    /** 走 5 页，把「机制」和「内容」两方面的观测值都记下来 */
    private Walk walkFivePages() throws Exception {
        Walk walk = new Walk();
        int maxIndex = 0;
        for (int p = 1; p <= PAGES; p++) {
            walk.maxIndexes.add(maxIndex);
            WereadRawResponse resp = client.fetchRaw(TYPE, TARGET_ID, maxIndex);
            assertTrue(resp.isSuccess(), "第 " + p + " 页请求应成功，实际 status="
                    + resp.getStatusCode());
            WereadPage page = WereadPage.parse(resp.getBody());

            walk.rawCounts.add(page.size());
            List<Book> books = parser.parseAll(page.getBooks());
            walk.parsedCounts.add(books.size());
            for (Book b : books) {
                Integer idx = b.getSearchIdx() == null ? -1 : b.getSearchIdx();
                walk.byId.put(b.getBookId(), idx);
                walk.idxOrder.add(idx);
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
        return walk;
    }

    /** 一次「走 5 页」的全部观测值 */
    private static final class Walk {

        /** {@code bookId → searchIdx}（同一 bookId 再次出现时覆盖为最新） */
        final Map<String, Integer> byId = new LinkedHashMap<String, Integer>();

        /** 按返回顺序记录的 {@code searchIdx}（含重复 bookId 的两次出现） */
        final List<Integer> idxOrder = new ArrayList<Integer>();

        /** 每页的原始节点数（{@link WereadPage#size()}，未解析） */
        final List<Integer> rawCounts = new ArrayList<Integer>();

        /** 每页解析出的本数（{@code parser.parseAll(...).size()}） */
        final List<Integer> parsedCounts = new ArrayList<Integer>();

        /** 每页请求用的 {@code maxIndex}，便于排查 */
        final List<Integer> maxIndexes = new ArrayList<Integer>();

        boolean isStrictlyIncreasing() {
            for (int i = 1; i < idxOrder.size(); i++) {
                if (idxOrder.get(i) <= idxOrder.get(i - 1)) {
                    return false;
                }
            }
            return true;
        }

        int distinctIdxCount() {
            return new LinkedHashSet<Integer>(idxOrder).size();
        }

        int minIdx() {
            int min = Integer.MAX_VALUE;
            for (Integer i : idxOrder) {
                min = Math.min(min, i);
            }
            return idxOrder.isEmpty() ? -1 : min;
        }

        int maxIdx() {
            int max = Integer.MIN_VALUE;
            for (Integer i : idxOrder) {
                max = Math.max(max, i);
            }
            return idxOrder.isEmpty() ? -1 : max;
        }
    }
}
