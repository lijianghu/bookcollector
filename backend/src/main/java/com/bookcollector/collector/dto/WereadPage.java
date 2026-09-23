package com.bookcollector.collector.dto;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.Getter;

/**
 * 微信读书列表接口的<b>响应信封</b>。
 *
 * <pre>
 * {
 *   uiInfo:     { styleType: "displayNewRating" },   ← 响应级样式提示，与书无关，丢弃
 *   synckey:    1789989022,
 *   books:      [ { bookInfo: {...}, searchIdx, type, readingCount }, ... ],
 *   hasMore:    true,
 *   totalCount: 54078
 * }
 * </pre>
 *
 * <p>把信封解析单独拎出来，是为了让 {@link CollectLoop} 只关心「翻页控制」，
 * 不用混着写 JSON 取值。
 */
@Getter
public class WereadPage {

    private final JSONArray books;
    private final long synckey;
    private final boolean hasMore;
    private final int totalCount;

    private WereadPage(JSONArray books, long synckey, boolean hasMore, int totalCount) {
        this.books = books;
        this.synckey = synckey;
        this.hasMore = hasMore;
        this.totalCount = totalCount;
    }

    /**
     * 解析响应体。
     *
     * <p>注意 {@code hasMore}：原 Python 写的是 {@code data.get('hasMore', False)}，
     * 而接口实际返回的是 <b>数字 1</b>（不是布尔 true）。fastjson2 的
     * {@code getBoolean} 对数字 0/1 能正确转换，这里用它。
     *
     * @throws IllegalArgumentException body 不是合法 JSON 对象时
     */
    public static WereadPage parse(String body) {
        if (body == null || body.trim().isEmpty()) {
            throw new IllegalArgumentException("响应体为空");
        }
        JSONObject root;
        try {
            root = JSON.parseObject(body);
        } catch (Exception e) {
            // 把原始响应的开头带进异常，方便判断是不是被拦了（返回了 HTML 验证页之类）
            String head = body.length() > 200 ? body.substring(0, 200) + "..." : body;
            throw new IllegalArgumentException("响应不是合法 JSON，开头为：" + head, e);
        }
        if (root == null) {
            throw new IllegalArgumentException("响应解析为 null");
        }

        JSONArray books = root.getJSONArray("books");
        if (books == null) {
            books = new JSONArray();
        }
        return new WereadPage(
                books,
                root.getLongValue("synckey", 0L),
                root.getBooleanValue("hasMore", false),
                root.getIntValue("totalCount", 0));
    }

    public boolean isEmpty() {
        return books == null || books.isEmpty();
    }

    public int size() {
        return books == null ? 0 : books.size();
    }

    /**
     * 本页最后一条记录的 {@code searchIdx} —— <b>这就是下一页的 {@code maxIndex}</b>。
     *
     * <p>返回 {@code null} 表示取不到（本页为空或最后一条缺字段）。
     * 调用方必须处理这种情况：取不到游标就不能推进，否则会死循环。
     */
    public Integer lastSearchIdx() {
        if (isEmpty()) {
            return null;
        }
        JSONObject last = books.getJSONObject(books.size() - 1);
        if (last == null) {
            return null;
        }
        return last.getInteger("searchIdx");
    }
}
