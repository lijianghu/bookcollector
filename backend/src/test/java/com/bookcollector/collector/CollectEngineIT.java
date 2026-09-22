package com.bookcollector.collector;

import com.bookcollector.audit.entity.ApiRequest;
import com.bookcollector.book.BookRepository;
import com.bookcollector.book.entity.Book;
import com.bookcollector.common.enums.TargetType;
import com.bookcollector.common.enums.TaskStatus;
import com.bookcollector.cursor.CursorStore;
import com.bookcollector.cursor.entity.CollectCursor;
import com.bookcollector.task.entity.TaskLog;
import com.bookcollector.task.entity.TaskRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2 采集引擎验收测试 —— <b>打真实接口</b>，不 mock。
 *
 * <p>对应 S2 的验收标准：
 * <ol>
 *   <li>对「文学」跑通 5 页，{@code books} 数量明显增长</li>
 *   <li><b>连跑两次，总数不变</b>（幂等）—— 这是整个采集链路最重要的性质</li>
 *   <li>{@code api_requests} 里每页一条记录，{@code responseTimeMs} 合理</li>
 * </ol>
 *
 * <p>类名以 {@code IT} 结尾，<b>不在 {@code mvn test} 的默认扫描范围里</b>
 * （默认只扫 {@code *Test} / {@code Test*} / {@code *Tests} / {@code *TestCase}）。
 * 因为它要联网、要跑十几秒，不该拖慢日常构建。显式运行：
 * <pre>tools/mvn8.sh -f backend/pom.xml test -Dtest=CollectEngineIT</pre>
 *
 * <p><b>它写入的是真实数据</b>：采集到的书会留在 {@code books} 集合里（这正是验收要求）。
 * 但测试自己产生的 {@code collect_task_runs} / {@code collect_task_logs} /
 * {@code api_requests} 会在结束后清掉。
 *
 * <h3>⚠️ 为什么断言不写「全局总数必须增长」</h3>
 * 四个测试打的是同一个目标（文学前 5 页 = 固定 100 本书），而 {@code books}
 * 是<b>跨测试、跨运行持久保留</b>的。所以：
 * <ul>
 *   <li>JUnit 5 默认<b>不保证</b>测试方法顺序，哪个先跑不定；</li>
 *   <li>第一次跑完这 100 本就在库里了，之后再跑总数不可能再增长。</li>
 * </ul>
 * 早期版本写成 {@code assertTrue(booksAfter > booksBefore)}，结果被真实的执行顺序
 * （2 → 4 → 3 → 1，{@code rerunIsIdempotent} 先跑）打脸 ——
 * 那次失败恰恰证明了幂等是对的，错的是断言。
 *
 * <p>现在断言的是<b>该目标自己的书</b>：按 {@code collectSource} 去 count。
 * 首次跑（库里还没有）走「增长」分支，重复跑走「幂等」分支，两种都成立。
 *
 * <h3>⚠️ 还有一个反直觉点：榜单会漂移</h3>
 * 实测（见 {@link S2PageStabilityIT}）「文学」分类的前 100 名在<b>几分钟内</b>就会换掉一两本。
 * 所以 {@code books} 的正确语义是「<b>历史累计的并集</b>」，不是「当前榜单的快照」——
 * 跨较长时间重采，总数可能 ±N，这不是 bug。
 * 「严格总数不变」只在<b>紧挨着的两次</b>之间成立，由 {@code rerunIsIdempotent} 验证。
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("S2 · 采集引擎验收（真实接口）")
class CollectEngineIT {

    /** 文学 */
    private static final TargetType TYPE = TargetType.CATEGORY;
    private static final String TARGET_ID = "300000";
    private static final String TARGET_NAME = "文学";
    private static final String TASK_ID = "it-s2-task";

    /** 由 {@link CollectCommand#sourceKey()} 派生：{@code category:300000} */
    private static final String SOURCE_KEY = "category:300000";

    /** 验收要求的页数 */
    private static final int PAGES = 5;

    @Autowired
    private CollectLoop collectLoop;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private CursorStore cursorStore;

    @Autowired
    private MongoTemplate mongoTemplate;

    private String runId;

    @BeforeEach
    void createRun() {
        TaskRun run = TaskRun.builder()
                .taskId(TASK_ID)
                .taskName("S2-IT-文学")
                .targetType(TYPE.name())
                .targetId(TARGET_ID)
                .targetName(TARGET_NAME)
                .startedAt(new Date())
                .status(TaskStatus.RUNNING.name())
                .pagesDone(0)
                .booksSaved(0L)
                .currentCursor(0)
                .lastProgressAt(new Date())
                .build();
        mongoTemplate.insert(run);
        runId = run.getId();
    }

    @AfterEach
    void cleanup() {
        if (runId != null) {
            mongoTemplate.remove(Query.query(Criteria.where("_id").is(runId)), TaskRun.class);
            mongoTemplate.remove(Query.query(Criteria.where("runId").is(runId)), TaskLog.class);
            mongoTemplate.remove(Query.query(Criteria.where("runId").is(runId)), ApiRequest.class);
        }
        mongoTemplate.remove(Query.query(Criteria.where("taskId").is(TASK_ID)), TaskRun.class);

        // 游标也要清掉。四个测试都从 reset 开始，跑完各自把游标留在不同位置，
        // 最后一个跑的是「只采 1 页」→ 游标停在 20，而库里已经有 100 本书。
        // 这个「游标 20 / 数据 100」的不一致状态会误导 UI 的断点续传页，
        // 所以测试自己收拾干净：只留下 books（那是验收要求），不留下半截游标。
        cursorStore.delete(TYPE, TARGET_ID);
    }

    // ==================================================================
    // 验收 1：跑通 5 页，books 增长
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("1. 对「文学」跑通 5 页，books 数量增长，游标推进")
    void collectFivePages() {
        long booksBefore = bookRepository.count();
        long taggedBefore = countBySource(SOURCE_KEY);

        CollectResult result = collectLoop.run(newCommand(true, PAGES));

        // ---- 结果本身 ----
        assertTrue(result.isSuccess(),
                "采集应成功，实际 errorMsg=" + result.getErrorMsg()
                        + " canceled=" + result.isCanceled());
        assertEquals(PAGES, result.getPagesDone(), "应正好采 " + PAGES + " 页");
        assertTrue(result.getBooksParsed() > 0, "应解析出图书");
        assertEquals(result.getBooksParsed(), result.getBooksSaved(),
                "本测试场景下解析数应等于写入数（全是新书或正常更新）");
        assertTrue(result.getTotalCount() != null && result.getTotalCount() > 0,
                "应拿到接口报告的 totalCount");

        // ---- 图书真的进库了 ----
        long booksAfter = bookRepository.count();
        long taggedAfter = countBySource(SOURCE_KEY);
        assertTrue(booksAfter >= booksBefore,
                "books 只增不减（upsert 从不删除）：采前 " + booksBefore + " → 采后 " + booksAfter);
        assertTrue(taggedAfter >= result.getBooksSaved(),
                "本次写入的 " + result.getBooksSaved() + " 本都应带上 collectSource=" + SOURCE_KEY
                        + "，实际该来源共 " + taggedAfter + " 本");
        assertTrue(taggedAfter <= booksAfter,
                "该来源的书不可能多于 books 总数");

        if (taggedBefore == 0) {
            // 全新库：这才是验收说的「books 数量明显增长」
            assertEquals(result.getBooksSaved(), booksAfter - booksBefore,
                    "全新库下，本次写入数应等于总数增量");
        } else {
            // 这个目标之前采过 → 对同一份响应的 upsert 是幂等的。
            //
            // ⚠️ 这里断言的是「只增不减」，不是「严格不变」——
            // 榜单会随时间漂移（见 S2PageStabilityIT）：几分钟内就可能有书进出前 100 名。
            // 所以重采同样范围时，总数可能比上次多出几本（历史遗留的并集只会变大）。
            // 「连跑两次总数不变」的严格版本由 rerunIsIdempotent 验证（那两次是紧挨着的）。
            assertTrue(booksAfter >= booksBefore,
                    "重采同样范围，books 总数不应减少");
        }

        // ---- 游标推进了 ----
        assertTrue(result.getCurrentCursor() > 0, "游标应被推进到 > 0");
        CollectCursor cursor = cursorStore.load(TYPE, TARGET_ID);
        assertNotNull(cursor, "游标记录应存在");
        assertEquals(result.getCurrentCursor(), cursor.getMaxIndex().intValue(),
                "落库的游标应与结果一致");
        assertEquals(result.getBooksSaved(), cursor.getTotalCollected().longValue(),
                "reset 后 totalCollected 应等于本次写入数");

        // ---- 验收 3：api_requests 每页一条 ----
        List<ApiRequest> audits = findAudit(runId);
        assertEquals(PAGES, audits.size(), "api_requests 应有 " + PAGES + " 条（每页一条）");
        for (ApiRequest a : audits) {
            assertEquals(200, a.getStatusCode().intValue(), "状态码应为 200");
            assertTrue(a.getResponseTimeMs() > 0, "耗时应 > 0");
            assertTrue(a.getResponseTimeMs() < 30_000, "耗时应 < 30s，实际 " + a.getResponseTimeMs());
            assertTrue(a.getResultCount() > 0, "每页应有数据");
            assertNotNull(a.getCreatedAt(), "createdAt 应由审计填充");
            assertNotNull(a.getSynckey(), "应记录 synckey");
            assertNotNull(a.getTotalCount(), "应记录 totalCount");
        }

        // ---- 进度落库 ----
        TaskRun run = mongoTemplate.findById(runId, TaskRun.class);
        assertNotNull(run, "run 记录应存在");
        assertEquals(PAGES, run.getPagesDone().intValue(), "run.pagesDone 应被刷新");
        assertEquals(result.getBooksSaved(), run.getBooksSaved().longValue(), "run.booksSaved 应被刷新");
        assertEquals(result.getCurrentCursor(), run.getCurrentCursor().intValue(), "run.currentCursor 应被刷新");
        assertNotNull(run.getLastProgressAt(), "lastProgressAt 应被刷新（判假死用）");

        // ---- 运行日志 ----
        long logCount = mongoTemplate.count(
                Query.query(Criteria.where("runId").is(runId)), TaskLog.class);
        assertTrue(logCount >= PAGES, "每页应至少有一条运行日志，实际 " + logCount);

        System.out.println("[S2-IT-1] 5 页采集成功 ✓  books " + booksBefore + " → " + booksAfter
                + "（该目标累计 " + taggedBefore + " → " + taggedAfter + " 本）"
                + "，游标 → " + result.getCurrentCursor()
                + "，totalCount=" + result.getTotalCount()
                + "，耗时 " + result.getCostMs() + "ms");
    }

    // ==================================================================
    // 验收 2：幂等 —— 连跑两次，总数不变
    // ==================================================================

    @Test
    @Order(2)
    @DisplayName("2. 幂等：同样 5 页连跑两次，books 总数不变")
    void rerunIsIdempotent() {
        // 第一次
        CollectResult first = collectLoop.run(newCommand(true, PAGES));
        assertTrue(first.isSuccess(), "第一次应成功：" + first.getErrorMsg());
        long afterFirst = bookRepository.count();
        assertTrue(first.getBooksSaved() > 0);

        // 第二次：完全相同的范围（resetCursor=true → 从 0 重采同样 5 页）
        CollectResult second = collectLoop.run(newCommand(true, PAGES));
        assertTrue(second.isSuccess(), "第二次应成功：" + second.getErrorMsg());
        long afterSecond = bookRepository.count();

        assertEquals(afterFirst, afterSecond,
                "★ 幂等失败：重采同样范围后 books 总数从 " + afterFirst + " 变成了 " + afterSecond
                        + "。注意两种可能：(1) upsert 真有问题；"
                        + "(2) 榜单恰好在两次之间漂移了（见 S2PageStabilityIT，概率很低）。"
                        + "连续跑一次通常就能区分。");

        // 第二次应该全是「更新」而不是「新增」
        assertEquals(second.getBooksParsed(), second.getBooksSaved(),
                "第二次也应全部处理成功");

        // 两页的 searchIdx 范围一致 → 解析出的条数应完全相同
        assertEquals(first.getBooksParsed(), second.getBooksParsed(),
                "同样 5 页应解析出同样多的记录");

        System.out.println("[S2-IT-2] 幂等 ✓  第一次后 " + afterFirst + " 本，第二次后 "
                + afterSecond + " 本，总数不变；两次各处理 " + first.getBooksSaved()
                + " / " + second.getBooksSaved() + " 条");
    }

    @Test
    @Order(3)
    @DisplayName("3. 断点续传：采 2 页后接着采，游标从上次的位置继续")
    void resumeFromCursor() {
        CollectResult first = collectLoop.run(newCommand(true, 2));
        assertTrue(first.isSuccess(), "第一次应成功：" + first.getErrorMsg());
        int cursorAfterTwoPages = first.getCurrentCursor();
        assertTrue(cursorAfterTwoPages > 0);

        // 不 reset → 从游标续传
        CollectResult second = collectLoop.run(newCommand(false, 2));
        assertTrue(second.isSuccess(), "续传应成功：" + second.getErrorMsg());
        assertTrue(second.getCurrentCursor() > cursorAfterTwoPages,
                "续传后游标应继续前进：" + cursorAfterTwoPages + " → " + second.getCurrentCursor());

        // totalCollected 是「累计」语义：续传不 reset，计数应接着往上加
        CollectCursor cursor = cursorStore.load(TYPE, TARGET_ID);
        assertNotNull(cursor);
        assertEquals(first.getBooksSaved() + second.getBooksSaved(),
                cursor.getTotalCollected().longValue(),
                "totalCollected 应跨运行累加：" + first.getBooksSaved() + " + "
                        + second.getBooksSaved());

        System.out.println("[S2-IT-3] 断点续传 ✓  2 页后游标 " + cursorAfterTwoPages
                + "，续传 2 页后 " + second.getCurrentCursor()
                + "，totalCollected 累计 " + cursor.getTotalCollected());
    }

    @Test
    @Order(4)
    @DisplayName("4. 页数上限：游标保留，可继续")
    void maxPagesStopsButKeepsCursor() {
        CollectResult result = collectLoop.run(newCommand(true, 1));
        assertTrue(result.isSuccess(), "应成功：" + result.getErrorMsg());
        assertEquals(1, result.getPagesDone());
        assertTrue(result.isHasMore(), "只采 1 页时 hasMore 应为 true（还有更多数据）");
        assertTrue(result.getCurrentCursor() > 0, "游标应保留");

        CollectCursor cursor = cursorStore.load(TYPE, TARGET_ID);
        assertNotNull(cursor);
        assertFalse(Boolean.TRUE.equals(cursor.getFinished()),
                "只采 1 页不应把游标标记为「已采到底」");

        System.out.println("[S2-IT-4] 页数上限 ✓  采 1 页后 hasMore=" + result.isHasMore()
                + "，游标 " + result.getCurrentCursor() + " 保留，finished=false");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private CollectCommand newCommand(boolean resetCursor, int maxPages) {
        return CollectCommand.builder()
                .runId(runId)
                .taskId(TASK_ID)
                .targetType(TYPE)
                .targetId(TARGET_ID)
                .targetName(TARGET_NAME)
                .maxPages(maxPages)
                .resetCursor(resetCursor)
                .build();
    }

    private List<ApiRequest> findAudit(String runId) {
        Query q = Query.query(Criteria.where("runId").is(runId));
        q.with(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.ASC, "pageIndex"));
        return mongoTemplate.find(q, ApiRequest.class);
    }

    /**
     * 数一数「这个目标贡献了多少本书」—— 按 {@code collectSource} 数组去重计数。
     *
     * <p>比全局 {@code count()} 靠谱：全局数会被别的测试、别的目标污染。
     */
    private long countBySource(String sourceKey) {
        return mongoTemplate.count(
                Query.query(Criteria.where("collectSource").is(sourceKey)), Book.class);
    }
}
