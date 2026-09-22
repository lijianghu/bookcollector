package com.bookcollector.collector;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bookcollector.book.entity.Book;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BookParser} 的回归测试 —— 用 20 本<b>真实</b>样本逐字段核对。
 *
 * <p>样本是原 Python 项目留下的 {@code books.json}（微信读书「文学」分类第一页的原始响应，
 * 20 条记录），拷到 {@code src/test/resources/} 作为基准。
 *
 * <h3>这个测试在防什么</h3>
 * 字段映射写错是<b>最隐蔽</b>的一类 bug —— 不报错、不崩溃，只是库里的数据悄悄错了。
 * 尤其是 {@code searchIdx}/{@code type}/{@code readingCount} 取自外层而不是
 * {@code bookInfo}，以及 {@code typeInfo ← 外层 type} 与 {@code type ← bookInfo.type}
 * 这对同名不同源的字段。所以这里用<b>映射表</b>而不是手写断言，
 * 并且额外断言「映射表覆盖了接口返回的全部字段」—— 漏一个字段测试就红。
 *
 * <p>不需要 Spring 上下文，毫秒级。
 */
@DisplayName("S2 · BookParser 字段映射回归")
class BookParserTest {

    private static final String SAMPLE = "books.json";

    /** 20 条样本记录的 bookInfo 字段并集 */
    private static final int UNION_FIELD_COUNT = 46;

    /**
     * 实测的字段数分布：43 个的有 10 条、44 个的有 9 条、45 个的有 1 条。
     *
     * <p><b>这是「46 是并集、不是每条都有」这个事实的护栏</b>。
     * 如果哪天有人把断言写成「每条都是 46」，这个测试会先红。
     */
    private static final Map<Integer, Integer> EXPECTED_DISTRIBUTION = new LinkedHashMap<Integer, Integer>();

    static {
        EXPECTED_DISTRIBUTION.put(43, 10);
        EXPECTED_DISTRIBUTION.put(44, 9);
        EXPECTED_DISTRIBUTION.put(45, 1);
    }

    private static JSONArray sampleBooks;
    private static final BookParser PARSER = new BookParser();

    @BeforeAll
    static void loadSample() throws IOException {
        InputStream in = BookParserTest.class.getClassLoader().getResourceAsStream(SAMPLE);
        assertNotNull(in, "测试资源 " + SAMPLE + " 不存在，应从原项目拷到 src/test/resources/");
        JSONObject root = JSON.parseObject(readFully(in));
        sampleBooks = root.getJSONArray("books");
        assertNotNull(sampleBooks, "样本里没有 books 数组");
        assertEquals(20, sampleBooks.size(), "样本应有 20 条记录");
    }

    // ==================================================================
    // 1. 字段覆盖度：接口返回的字段，解析器一个都不能漏
    // ==================================================================

    @Test
    @DisplayName("1. 映射表覆盖接口返回的全部 46 个字段")
    void mappingCoversEveryApiField() {
        // 接口返回的 bookInfo 字段并集
        Set<String> apiKeys = new TreeSet<String>();
        for (int i = 0; i < sampleBooks.size(); i++) {
            apiKeys.addAll(sampleBooks.getJSONObject(i).getJSONObject("bookInfo").keySet());
        }
        assertEquals(UNION_FIELD_COUNT, apiKeys.size(),
                "bookInfo 字段并集应为 " + UNION_FIELD_COUNT + "，实际 " + apiKeys.size()
                        + "：" + apiKeys);

        // 解析器实际消费的字段
        Set<String> consumed = consumedBookInfoKeys();

        Set<String> missing = new TreeSet<String>(apiKeys);
        missing.removeAll(consumed);
        Set<String> extra = new TreeSet<String>(consumed);
        extra.removeAll(apiKeys);

        assertTrue(missing.isEmpty(),
                "❌ 接口返回了但解析器没映射的字段：" + missing
                        + "\n（每漏一个字段就是一处静默的数据丢失）");
        assertTrue(extra.isEmpty(),
                "❌ 解析器映射了但接口样本里不存在的字段（可能是字段名拼错了）：" + extra);

        System.out.println("[S2-1] 字段覆盖度 ✓  接口 " + apiKeys.size()
                + " 个字段全部被解析器消费，无遗漏、无拼写错误");
    }

    @Test
    @DisplayName("1b. 映射表本身 = 44 个标量 + 2 个嵌套 = 46")
    void mappingTableSize() {
        assertEquals(44, SCALAR_FIELDS.size(),
                "标量字段映射表应为 44 项（46 减去 categories 和 newRatingDetail 两个嵌套结构）");
        assertEquals(46, SCALAR_FIELDS.size() + 2,
                "业务字段总数应为 46");
        System.out.println("[S2-1b] 映射表 44 标量 + 2 嵌套 = 46 业务字段 ✓");
    }

    // ==================================================================
    // 2. 逐字段核对（20 条记录 × 44 个标量字段 = 880 次断言）
    // ==================================================================

    @Test
    @DisplayName("2. 20 条样本 × 44 个标量字段，逐条逐字段核对")
    void everyScalarFieldMatches() {
        List<Book> books = PARSER.parseAll(sampleBooks);
        assertEquals(20, books.size(), "20 条记录都应解析成功");

        int assertions = 0;
        List<String> failures = new ArrayList<String>();

        for (int i = 0; i < books.size(); i++) {
            JSONObject node = sampleBooks.getJSONObject(i);
            Book book = books.get(i);

            for (Field f : SCALAR_FIELDS) {
                Object expected = normalize(resolve(node, f.path));
                Object actual = normalize(f.getter.get(book));
                assertions++;
                if (!java.util.Objects.equals(expected, actual)) {
                    failures.add(String.format(
                            "  记录[%d] %s：期望 %s（来自 %s），实际 %s",
                            i + 1, f.label, expected, f.path, actual));
                }
            }
        }

        assertTrue(failures.isEmpty(),
                "❌ 字段不一致 " + failures.size() + " 处：\n" + String.join("\n", failures));
        System.out.println("[S2-2] " + assertions + " 次标量字段断言全部通过（20 条 × 44 字段）");
    }

    // ==================================================================
    // 3. 嵌套结构
    // ==================================================================

    @Test
    @DisplayName("3. 嵌套结构：categories / newRatingDetail 逐元素核对")
    void nestedStructuresMatch() {
        List<Book> books = PARSER.parseAll(sampleBooks);

        int catCount = 0;
        int ratingCount = 0;
        for (int i = 0; i < books.size(); i++) {
            JSONObject info = sampleBooks.getJSONObject(i).getJSONObject("bookInfo");
            Book book = books.get(i);

            // ---- categories ----
            JSONArray rawCats = info.getJSONArray("categories");
            List<Book.BookCategory> cats = book.getCategories();
            if (rawCats == null || rawCats.isEmpty()) {
                assertNull(cats, "记录[" + (i + 1) + "] categories 应为 null");
            } else {
                assertNotNull(cats, "记录[" + (i + 1) + "] categories 不应为 null");
                assertEquals(rawCats.size(), cats.size(),
                        "记录[" + (i + 1) + "] categories 元素个数不一致");
                for (int j = 0; j < rawCats.size(); j++) {
                    JSONObject rc = rawCats.getJSONObject(j);
                    Book.BookCategory ac = cats.get(j);
                    assertEquals(rc.getInteger("categoryId"), ac.getCategoryId(),
                            "记录[" + (i + 1) + "] categories[" + j + "].categoryId");
                    assertEquals(rc.getInteger("subCategoryId"), ac.getSubCategoryId(),
                            "记录[" + (i + 1) + "] categories[" + j + "].subCategoryId");
                    assertEquals(rc.getInteger("categoryType"), ac.getCategoryType(),
                            "记录[" + (i + 1) + "] categories[" + j + "].categoryType");
                    assertEquals(BookParser.cleanValue(rc.getString("title")), ac.getTitle(),
                            "记录[" + (i + 1) + "] categories[" + j + "].title");
                    catCount++;
                }
            }

            // ---- newRatingDetail ----
            JSONObject rawRd = info.getJSONObject("newRatingDetail");
            Book.RatingDetail rd = book.getNewRatingDetail();
            if (rawRd == null || rawRd.isEmpty()) {
                assertNull(rd, "记录[" + (i + 1) + "] newRatingDetail 应为 null");
            } else {
                assertNotNull(rd, "记录[" + (i + 1) + "] newRatingDetail 不应为 null");
                assertEquals(rawRd.getInteger("good"), rd.getGood());
                assertEquals(rawRd.getInteger("fair"), rd.getFair());
                assertEquals(rawRd.getInteger("poor"), rd.getPoor());
                assertEquals(rawRd.getInteger("recent"), rd.getRecent());
                assertEquals(BookParser.cleanValue(rawRd.getString("title")), rd.getTitle());
                ratingCount++;
            }
        }

        System.out.println("[S2-3] 嵌套结构 ✓  categories 共 " + catCount
                + " 个元素、newRatingDetail 共 " + ratingCount + " 条，全部逐字段一致");
    }

    @Test
    @DisplayName("4. 4 个「原 Python 丢弃」的扩展字段也被正确解析")
    void extraFieldsParsed() {
        List<Book> books = PARSER.parseAll(sampleBooks);

        // 这 4 个字段在样本里都是 20/20 出现
        for (int i = 0; i < books.size(); i++) {
            JSONObject info = sampleBooks.getJSONObject(i).getJSONObject("bookInfo");
            Book book = books.get(i);

            JSONObject rawMf = info.getJSONObject("maxFreeInfo");
            assertNotNull(book.getMaxFreeInfo(), "记录[" + (i + 1) + "] maxFreeInfo 未解析");
            assertEquals(rawMf.getInteger("maxFreeChapterIdx"),
                    book.getMaxFreeInfo().getMaxFreeChapterIdx());
            assertEquals(rawMf.getInteger("maxFreeChapterUid"),
                    book.getMaxFreeInfo().getMaxFreeChapterUid());
            assertEquals(rawMf.getInteger("maxFreeChapterRatio"),
                    book.getMaxFreeInfo().getMaxFreeChapterRatio());

            assertNotNull(book.getCopyrightChapterUids(),
                    "记录[" + (i + 1) + "] copyrightChapterUids 未解析");
            assertEquals(info.getJSONArray("copyrightChapterUids").size(),
                    book.getCopyrightChapterUids().size());
        }

        // 这两个是「可选」的，样本里只有少数几条有 —— 验证「有就解析、没有就 null」
        int lPush = 0;
        int vids = 0;
        for (int i = 0; i < books.size(); i++) {
            JSONObject info = sampleBooks.getJSONObject(i).getJSONObject("bookInfo");
            Book book = books.get(i);
            if (info.containsKey("lPushName")) {
                assertEquals(BookParser.cleanValue(info.getString("lPushName")), book.getLPushName());
                lPush++;
            } else {
                assertNull(book.getLPushName());
            }
            if (info.containsKey("authorVids")) {
                assertEquals(BookParser.cleanValue(info.getString("authorVids")), book.getAuthorVids());
                vids++;
            } else {
                assertNull(book.getAuthorVids());
            }
        }
        System.out.println("[S2-4] 扩展字段 ✓  maxFreeInfo/copyrightChapterUids 20/20，"
                + "lPushName " + lPush + "/20，authorVids " + vids + "/20");
    }

    // ==================================================================
    // 5. 字段数分布（46 是并集的护栏）
    // ==================================================================

    @Test
    @DisplayName("5. 字段数分布 43/44/45 —— 46 是并集不是每条的字段数")
    void fieldCountDistribution() {
        Map<Integer, Integer> dist = new TreeMap<Integer, Integer>();
        for (int i = 0; i < sampleBooks.size(); i++) {
            int n = sampleBooks.getJSONObject(i).getJSONObject("bookInfo").size();
            Integer old = dist.get(n);
            dist.put(n, old == null ? 1 : old + 1);
        }
        assertEquals(EXPECTED_DISTRIBUTION, dist,
                "字段数分布与预期不符。记住：46 是并集，单条记录是浮动的");

        System.out.println("[S2-5] 字段数分布 ✓  " + dist
                + "（并集 46 = 43 必现 + translator/lPushName/authorVids 3 个可选）");
    }

    // ==================================================================
    // 6. cleanValue 单元测试
    // ==================================================================

    @Test
    @DisplayName("6. cleanValue：'' / 'null' / 'undefined' / 空白 → null")
    void cleanValueRules() {
        assertNull(BookParser.cleanValue(null), "null 应保持 null");
        assertNull(BookParser.cleanValue(""), "空串应转 null");
        assertNull(BookParser.cleanValue("   "), "纯空白应转 null");
        assertNull(BookParser.cleanValue("\t\n"), "制表符换行应转 null");
        assertNull(BookParser.cleanValue("null"), "字面量 'null' 应转 null（接口真的会返回它）");
        assertNull(BookParser.cleanValue("NULL"), "'NULL' 大小写不敏感");
        assertNull(BookParser.cleanValue("undefined"), "字面量 'undefined' 应转 null");

        // ⚠️ 这几个不能变成 null，否则会丢数据
        assertEquals("0", BookParser.cleanValue("0"), "'0' 是有效字符串，不能转 null");
        assertEquals("false", BookParser.cleanValue("false"), "'false' 是有效字符串");
        assertEquals("nullx", BookParser.cleanValue("nullx"), "前缀匹配不算");
        assertEquals("null null", BookParser.cleanValue("null null"), "只有整体等于才算");
        assertEquals("abc", BookParser.cleanValue("  abc  "), "应 trim 首尾空白");
        assertEquals("a b", BookParser.cleanValue("a b"), "中间空格保留");

        System.out.println("[S2-6] cleanValue 规则 ✓（含 '0'/'false' 不误伤、trim 生效）");
    }

    // ==================================================================
    // 7. 脏数据容错
    // ==================================================================

    @Test
    @DisplayName("7. 脏数据容错：缺 bookId 跳过、脏记录不影响整页")
    void dirtyDataTolerance() {
        // 缺 bookId → 返回 null，不抛异常
        JSONObject noId = JSON.parseObject("{\"bookInfo\":{\"title\":\"无 ID 的书\"},\"searchIdx\":1}");
        assertNull(PARSER.parse(noId), "缺 bookId 应返回 null");

        // bookId 是字符串 "null" → 也视为缺失
        JSONObject nullId = JSON.parseObject("{\"bookInfo\":{\"bookId\":\"null\",\"title\":\"x\"}}");
        assertNull(PARSER.parse(nullId), "bookId 为字面量 'null' 应视为缺失");

        // 没有 bookInfo 节点
        assertNull(PARSER.parse(JSON.parseObject("{\"searchIdx\":1}")), "缺 bookInfo 应返回 null");
        assertNull(PARSER.parse(null), "null 入参应返回 null");

        // 一页里混入脏数据 → 好的照常解析，坏的跳过
        JSONArray dirty = JSON.parseObject(
                "{\"books\":["
                        + "{\"bookInfo\":{\"bookId\":\"ok1\",\"title\":\"好书1\"}},"
                        + "{\"bookInfo\":{\"title\":\"无ID\"}},"
                        + "{\"nobookInfo\":true},"
                        + "{\"bookInfo\":{\"bookId\":\"ok2\",\"title\":\"好书2\"}}"
                        + "]}").getJSONArray("books");
        List<Book> parsed = PARSER.parseAll(dirty);
        assertEquals(2, parsed.size(), "4 条里应有 2 条解析成功");
        assertEquals("ok1", parsed.get(0).getBookId());
        assertEquals("ok2", parsed.get(1).getBookId());

        // 空数组
        assertTrue(PARSER.parseAll(new JSONArray()).isEmpty());
        assertTrue(PARSER.parseAll(null).isEmpty());

        System.out.println("[S2-7] 脏数据容错 ✓（缺 bookId / 缺 bookInfo / 混入脏记录都不影响整页）");
    }

    // ==================================================================
    // 8. 外层字段不与 bookInfo 同名字段串台
    // ==================================================================

    @Test
    @DisplayName("8. typeInfo ← 外层 type，type ← bookInfo.type（同名不同源）")
    void outerTypeIsNotInnerType() {
        boolean sawDifference = false;
        for (int i = 0; i < sampleBooks.size(); i++) {
            JSONObject node = sampleBooks.getJSONObject(i);
            JSONObject info = node.getJSONObject("bookInfo");
            Book book = PARSER.parse(node);

            assertEquals(node.getInteger("searchIdx"), book.getSearchIdx(),
                    "记录[" + (i + 1) + "] searchIdx 应取自外层");
            assertEquals(node.getInteger("readingCount"), book.getReadingCount(),
                    "记录[" + (i + 1) + "] readingCount 应取自外层");
            assertEquals(node.getInteger("type"), book.getTypeInfo(),
                    "记录[" + (i + 1) + "] typeInfo 应取自外层 type");
            assertEquals(info.getInteger("type"), book.getType(),
                    "记录[" + (i + 1) + "] type 应取自 bookInfo.type");

            if (!java.util.Objects.equals(node.getInteger("type"), info.getInteger("type"))) {
                sawDifference = true;
            }
        }
        // 样本里外层 type 恒为 0、bookInfo.type 也恒为 0，所以这里只做断言，不强制有差异
        System.out.println("[S2-8] 外层字段归属正确 ✓  外层 type 与 bookInfo.type 在样本中"
                + (sawDifference ? "存在差异（已分别映射）" : "恰好相同（但映射路径已分别验证）"));
    }

    // ==================================================================
    // 字段映射表
    // ==================================================================

    private interface Getter {
        Object get(Book book);
    }

    private static final class Field {
        final String path;
        final Getter getter;
        final String label;

        Field(String path, Getter getter, String label) {
            this.path = path;
            this.getter = getter;
            this.label = label;
        }
    }

    /**
     * 44 个标量字段的映射表 —— 每一项就是一条「接口路径 → 实体 getter」的契约。
     *
     * <p>加上 {@code categories} 和 {@code newRatingDetail} 两个嵌套结构，
     * 正好覆盖 46 个业务字段。
     */
    private static final List<Field> SCALAR_FIELDS = Arrays.asList(
            // ---- 与 bookInfo 1:1（40 个）----
            new Field("bookInfo.bookId", b -> b.getBookId(), "bookId"),
            new Field("bookInfo.title", b -> b.getTitle(), "title"),
            new Field("bookInfo.author", b -> b.getAuthor(), "author"),
            new Field("bookInfo.translator", b -> b.getTranslator(), "translator"),
            new Field("bookInfo.cover", b -> b.getCover(), "cover"),
            new Field("bookInfo.version", b -> b.getVersion(), "version"),
            new Field("bookInfo.format", b -> b.getFormat(), "format"),
            new Field("bookInfo.type", b -> b.getType(), "type ← bookInfo.type"),
            new Field("bookInfo.price", b -> b.getPrice(), "price"),
            new Field("bookInfo.originalPrice", b -> b.getOriginalPrice(), "originalPrice"),
            new Field("bookInfo.soldout", b -> b.getSoldout(), "soldout"),
            new Field("bookInfo.bookStatus", b -> b.getBookStatus(), "bookStatus"),
            new Field("bookInfo.payingStatus", b -> b.getPayingStatus(), "payingStatus"),
            new Field("bookInfo.payType", b -> b.getPayType(), "payType"),
            new Field("bookInfo.intro", b -> b.getIntro(), "intro"),
            new Field("bookInfo.centPrice", b -> b.getCentPrice(), "centPrice"),
            new Field("bookInfo.finished", b -> b.getFinished(), "finished"),
            new Field("bookInfo.maxFreeChapter", b -> b.getMaxFreeChapter(), "maxFreeChapter"),
            new Field("bookInfo.free", b -> b.getFree(), "free"),
            new Field("bookInfo.mcardDiscount", b -> b.getMcardDiscount(), "mcardDiscount"),
            new Field("bookInfo.ispub", b -> b.getIspub(), "ispub"),
            new Field("bookInfo.extra_type", b -> b.getExtraType(), "extra_type → extraType"),
            new Field("bookInfo.cpid", b -> b.getCpid(), "cpid"),
            new Field("bookInfo.publishTime", b -> b.getPublishTime(), "publishTime"),
            new Field("bookInfo.category", b -> b.getCategory(), "category"),
            new Field("bookInfo.hasLecture", b -> b.getHasLecture(), "hasLecture"),
            new Field("bookInfo.lastChapterIdx", b -> b.getLastChapterIdx(), "lastChapterIdx"),
            new Field("bookInfo.blockSaveImg", b -> b.getBlockSaveImg(), "blockSaveImg"),
            new Field("bookInfo.language", b -> b.getLanguage(), "language"),
            new Field("bookInfo.isTraditionalChinese", b -> b.getIsTraditionalChinese(),
                    "isTraditionalChinese"),
            new Field("bookInfo.hideUpdateTime", b -> b.getHideUpdateTime(), "hideUpdateTime"),
            new Field("bookInfo.isEPUBComics", b -> b.getIsEpubComics(), "isEPUBComics → isEpubComics"),
            new Field("bookInfo.isVerticalLayout", b -> b.getIsVerticalLayout(), "isVerticalLayout"),
            new Field("bookInfo.isShowTTS", b -> b.getIsShowTts(), "isShowTTS → isShowTts"),
            new Field("bookInfo.webBookControl", b -> b.getWebBookControl(), "webBookControl"),
            new Field("bookInfo.selfProduceIncentive", b -> b.getSelfProduceIncentive(),
                    "selfProduceIncentive"),
            new Field("bookInfo.isAutoDownload", b -> b.getIsAutoDownload(), "isAutoDownload"),
            new Field("bookInfo.newRating", b -> b.getNewRating(), "newRating"),
            new Field("bookInfo.newRatingCount", b -> b.getNewRatingCount(), "newRatingCount"),

            // ---- 拍平 / 派生（3 个）----
            new Field("bookInfo.paperBook.skuId", b -> b.getPaperBookSkuId(),
                    "paperBook.skuId → paperBookSkuId"),
            new Field("bookInfo.newRatingDetail.title", b -> b.getNewRatingTitle(),
                    "newRatingDetail.title → newRatingTitle"),

            // ---- 取自外层记录（3 个）----
            new Field("searchIdx", b -> b.getSearchIdx(), "searchIdx ← 外层"),
            new Field("type", b -> b.getTypeInfo(), "type ← 外层 → typeInfo"),
            new Field("readingCount", b -> b.getReadingCount(), "readingCount ← 外层")
    );

    /** 解析器消费的 bookInfo 一级字段名（用于覆盖度检查） */
    private static Set<String> consumedBookInfoKeys() {
        Set<String> keys = new HashSet<String>();
        for (Field f : SCALAR_FIELDS) {
            if (!f.path.startsWith("bookInfo.")) {
                continue;
            }
            String rest = f.path.substring("bookInfo.".length());
            int dot = rest.indexOf('.');
            keys.add(dot < 0 ? rest : rest.substring(0, dot));
        }
        // 两个嵌套结构
        keys.add("categories");
        keys.add("newRatingDetail");
        // 4 个扩展字段
        keys.add("maxFreeInfo");
        keys.add("copyrightChapterUids");
        keys.add("lPushName");
        keys.add("authorVids");
        return keys;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 按 "a.b.c" 路径取值 */
    private static Object resolve(JSONObject node, String path) {
        String[] parts = path.split("\\.");
        Object cur = node;
        for (String p : parts) {
            if (!(cur instanceof JSONObject)) {
                return null;
            }
            cur = ((JSONObject) cur).get(p);
        }
        return cur;
    }

    /**
     * 归一化后再比较：数值统一成 Long、字符串走 cleanValue。
     * 否则 fastjson2 给的 {@code Integer(35)} 和实体里的 {@code Long} 会因为类型不同而误报。
     */
    private static Object normalize(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean) {
            return v;
        }
        if (v instanceof Number) {
            Number n = (Number) v;
            if (v instanceof Double || v instanceof Float) {
                return n.doubleValue();
            }
            return n.longValue();
        }
        if (v instanceof String) {
            return BookParser.cleanValue((String) v);
        }
        return v;
    }

    private static String readFully(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
