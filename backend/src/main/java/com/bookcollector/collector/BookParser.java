package com.bookcollector.collector;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bookcollector.book.entity.Book;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 微信读书接口响应 → {@link Book} 实体。
 *
 * <h3>字段口径</h3>
 * 严格对齐原 {@code book_api_client.py} 的 {@code BookInfo}（46 个字段）。
 * 三个<b>容易搞错</b>的点：
 * <ol>
 *   <li>{@code searchIdx} / {@code type} / {@code readingCount} 取自<b>外层记录</b>，
 *       不在 {@code bookInfo} 里。其中外层的 {@code type} 映射到
 *       {@link Book#getTypeInfo()}，<b>不是</b> {@link Book#getType()}
 *       （后者来自 {@code bookInfo.type}）。</li>
 *   <li>{@code paperBook.skuId} 拍平成 {@link Book#getPaperBookSkuId()}。</li>
 *   <li>{@code newRatingDetail.title} 拍平成 {@link Book#getNewRatingTitle()}，
 *       同时整个 {@code newRatingDetail} 对象也保留为
 *       {@link Book#getNewRatingDetail()}。</li>
 * </ol>
 *
 * <h3>{@link #cleanValue} 的必要性</h3>
 * 微信读书接口<b>真的会返回字符串 {@code "null"} 和 {@code "undefined"}</b>
 * （不是 JSON null）。不做清洗就会在库里存一堆字面量 {@code "null"}，
 * 前端显示成 "null" 文本。
 *
 * <p>原 Python 的 {@code clean_value} 只把 {@code '' / 'null' / 'undefined'} 转成 None。
 * 这里<b>额外加了 trim</b>（原版没有），并<b>返回 trim 后的值</b>：
 * 接口数据里的首尾空白是噪声，去掉更干净。这是刻意偏离原实现的一处，
 * 回归测试 {@code BookParserTest} 把这个行为钉住了。
 */
@Component
public class BookParser {

    private static final Logger log = LoggerFactory.getLogger(BookParser.class);

    /** 字符串字面量 {@code "null"} —— 接口真的会返回它 */
    private static final String LITERAL_NULL = "null";
    /** 字符串字面量 {@code "undefined"} */
    private static final String LITERAL_UNDEFINED = "undefined";

    /**
     * 解析一页的全部图书。
     *
     * <p>单本解析失败<b>只跳过该本并记 WARN</b>，不影响整页 ——
     * 不能因为一本书的脏数据搞挂整个采集任务。
     *
     * @param nodes 响应里的 {@code books} 数组，元素形如
     *              {@code {bookInfo: {...}, searchIdx, type, readingCount}}
     * @return 解析成功的图书列表（顺序与入参一致）
     */
    public List<Book> parseAll(JSONArray nodes) {
        List<Book> result = new ArrayList<Book>();
        if (nodes == null || nodes.isEmpty()) {
            return result;
        }
        for (int i = 0; i < nodes.size(); i++) {
            JSONObject node = null;
            try {
                node = nodes.getJSONObject(i);
                Book book = parse(node);
                if (book != null) {
                    result.add(book);
                }
            } catch (Exception e) {
                // 记 WARN 后继续，不要抛
                String hint = node == null ? "?" : String.valueOf(node.get("searchIdx"));
                log.warn("解析第 {} 条（searchIdx={}）失败，已跳过：{}", i + 1, hint, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 解析单条记录。
     *
     * @return 解析结果；{@code bookId} 缺失时返回 {@code null}（没有 bookId 无法 upsert）
     */
    public Book parse(JSONObject node) {
        if (node == null) {
            return null;
        }
        JSONObject info = node.getJSONObject("bookInfo");
        if (info == null) {
            log.warn("记录里没有 bookInfo 节点，已跳过");
            return null;
        }

        String bookId = cleanValue(info.getString("bookId"));
        if (bookId == null) {
            log.warn("记录缺少 bookId，已跳过（title={}）", info.getString("title"));
            return null;
        }

        return Book.builder()
                // ---- 与 bookInfo 1:1 映射（40 个）----
                .bookId(bookId)
                .title(cleanValue(info.getString("title")))
                .author(cleanValue(info.getString("author")))
                .translator(cleanValue(info.getString("translator")))
                .cover(cleanValue(info.getString("cover")))
                .version(info.getLong("version"))
                .format(cleanValue(info.getString("format")))
                .type(info.getInteger("type"))
                .price(info.getInteger("price"))
                .originalPrice(info.getInteger("originalPrice"))
                .soldout(info.getInteger("soldout"))
                .bookStatus(info.getInteger("bookStatus"))
                .payingStatus(info.getInteger("payingStatus"))
                .payType(info.getLong("payType"))
                .intro(cleanValue(info.getString("intro")))
                .centPrice(info.getInteger("centPrice"))
                .finished(info.getInteger("finished"))
                .maxFreeChapter(info.getInteger("maxFreeChapter"))
                .free(info.getInteger("free"))
                .mcardDiscount(info.getInteger("mcardDiscount"))
                .ispub(info.getInteger("ispub"))
                .extraType(info.getInteger("extra_type"))
                .cpid(info.getLong("cpid"))
                .publishTime(cleanValue(info.getString("publishTime")))
                .category(cleanValue(info.getString("category")))
                .categories(parseCategories(info.getJSONArray("categories")))
                .hasLecture(info.getInteger("hasLecture"))
                .lastChapterIdx(info.getInteger("lastChapterIdx"))
                .blockSaveImg(info.getInteger("blockSaveImg"))
                .language(cleanValue(info.getString("language")))
                .isTraditionalChinese(info.getBoolean("isTraditionalChinese"))
                .hideUpdateTime(info.getBoolean("hideUpdateTime"))
                .isEpubComics(info.getInteger("isEPUBComics"))
                .isVerticalLayout(info.getInteger("isVerticalLayout"))
                .isShowTts(info.getInteger("isShowTTS"))
                .webBookControl(info.getInteger("webBookControl"))
                .selfProduceIncentive(info.getBoolean("selfProduceIncentive"))
                .isAutoDownload(info.getInteger("isAutoDownload"))
                .newRating(info.getInteger("newRating"))
                .newRatingCount(info.getInteger("newRatingCount"))

                // ---- 由嵌套结构拍平 / 派生（3 个）----
                .paperBookSkuId(parsePaperBookSkuId(info))
                .newRatingTitle(parseNewRatingTitle(info))
                .newRatingDetail(parseRatingDetail(info.getJSONObject("newRatingDetail")))

                // ---- 取自外层记录，不在 bookInfo 里（3 个）----
                // ⚠️ typeInfo 取的是外层 type，别和上面的 .type(info.getInteger("type")) 搞混
                .searchIdx(node.getInteger("searchIdx"))
                .typeInfo(node.getInteger("type"))
                .readingCount(node.getInteger("readingCount"))

                // ---- 原 Python 丢弃、本次保留的 4 个扩展字段 ----
                .maxFreeInfo(parseMaxFreeInfo(info.getJSONObject("maxFreeInfo")))
                .copyrightChapterUids(parseIntList(info.getJSONArray("copyrightChapterUids")))
                .lPushName(cleanValue(info.getString("lPushName")))
                .authorVids(cleanValue(info.getString("authorVids")))
                .build();
    }

    // ==================================================================
    // 字符串清洗
    // ==================================================================

    /**
     * 清洗字符串字段。
     *
     * <pre>
     * null / "" / "null" / "undefined" / 纯空白   →  null
     * 其余                                        →  trim 后的值
     * </pre>
     *
     * <p><b>只对字符串字段调用</b>。数值字段（如 {@code price=0}）不能走这里 ——
     * 0 是有效值，转成 null 会丢数据。
     */
    public static String cleanValue(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()
                || LITERAL_NULL.equalsIgnoreCase(trimmed)
                || LITERAL_UNDEFINED.equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }

    // ==================================================================
    // 嵌套结构
    // ==================================================================

    /** {@code bookInfo.categories[]} —— 平台给这本书打的分类标签 */
    private List<Book.BookCategory> parseCategories(JSONArray arr) {
        if (arr == null || arr.isEmpty()) {
            return null;
        }
        List<Book.BookCategory> list = new ArrayList<Book.BookCategory>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JSONObject c = arr.getJSONObject(i);
            if (c == null) {
                continue;
            }
            list.add(Book.BookCategory.builder()
                    .categoryId(c.getInteger("categoryId"))
                    .subCategoryId(c.getInteger("subCategoryId"))
                    .categoryType(c.getInteger("categoryType"))
                    .title(cleanValue(c.getString("title")))
                    .build());
        }
        return list.isEmpty() ? null : list;
    }

    /** {@code bookInfo.newRatingDetail} —— 评分分布 */
    private Book.RatingDetail parseRatingDetail(JSONObject rd) {
        if (rd == null || rd.isEmpty()) {
            return null;
        }
        return Book.RatingDetail.builder()
                .good(rd.getInteger("good"))
                .fair(rd.getInteger("fair"))
                .poor(rd.getInteger("poor"))
                .recent(rd.getInteger("recent"))
                .title(cleanValue(rd.getString("title")))
                .build();
    }

    /** {@code bookInfo.maxFreeInfo} —— 免费章节详情（原 Python 丢弃的字段之一） */
    private Book.MaxFreeInfo parseMaxFreeInfo(JSONObject mf) {
        if (mf == null || mf.isEmpty()) {
            return null;
        }
        return Book.MaxFreeInfo.builder()
                .maxFreeChapterIdx(mf.getInteger("maxFreeChapterIdx"))
                .maxFreeChapterUid(mf.getInteger("maxFreeChapterUid"))
                .maxFreeChapterRatio(mf.getInteger("maxFreeChapterRatio"))
                .build();
    }

    /** {@code bookInfo.paperBook.skuId} → 拍平成字符串 */
    private String parsePaperBookSkuId(JSONObject info) {
        JSONObject pb = info.getJSONObject("paperBook");
        if (pb == null) {
            return null;
        }
        return cleanValue(pb.getString("skuId"));
    }

    /** {@code bookInfo.newRatingDetail.title} → 拍平成字符串，如 "神作" */
    private String parseNewRatingTitle(JSONObject info) {
        JSONObject rd = info.getJSONObject("newRatingDetail");
        if (rd == null) {
            return null;
        }
        return cleanValue(rd.getString("title"));
    }

    /** {@code bookInfo.copyrightChapterUids} —— 整数数组（原 Python 丢弃的字段之一） */
    private List<Integer> parseIntList(JSONArray arr) {
        if (arr == null || arr.isEmpty()) {
            return null;
        }
        List<Integer> list = new ArrayList<Integer>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            Integer v = arr.getInteger(i);
            if (v != null) {
                list.add(v);
            }
        }
        return list.isEmpty() ? null : list;
    }
}
