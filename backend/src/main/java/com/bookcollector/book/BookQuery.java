package com.bookcollector.book;

/**
 * 图书查询条件对象。
 *
 * <p>把「前端传什么」和「Mongo 怎么查」解耦：Controller 收一个 BookQuery，
 * {@code BookRepository} 负责翻译成 {@code Criteria}。
 *
 * <h3>刻意不支持的查询</h3>
 * <b>不做模糊搜索</b>（书名/作者 LIKE）。原 Python 用 {@code category LIKE '%x%'}，
 * 在 MongoDB 里要写正则，而用户明确说了「不需要模糊搜索，不用考虑 mongodb 的性能」。
 * 少一个能力比多一个全表扫的正则更值。
 *
 * <p>因此 {@link #author} 是<b>精确匹配</b>而不是包含匹配 —— 与「不做模糊搜索」
 * 这条约定保持一致，避免这里偷偷长出一个全表扫。
 *
 * <p><b>⚠️ FR-B2 的「字数区间」没有实现</b>：微信读书这个接口返回的
 * {@code bookInfo} 里<b>没有字数字段</b>（已用 {@code books.json} 的 20 本书样本
 * 逐个核对过 43 个键）。没有数据源就没有筛选可言，用阅读人数下限
 * （{@link #minReadingCount}）替代「热度」这个维度。要真做字数，得换接口。
 *
 * <p>所有字段都是<b>与</b>关系（AND）。多个条件之间没有 OR 语义。
 */
public class BookQuery {

    /** 每页最大条数，防止前端传 size=100000 把服务打爆 */
    public static final int MAX_SIZE = 200;

    /** 默认每页条数 */
    public static final int DEFAULT_SIZE = 20;

    /** 按平台分类 ID 精确筛（匹配 categories.categoryId，不是字符串前缀匹配） */
    private Integer categoryId;

    /** 评分下限（含）。★ 路径是<b>顶层</b> {@code newRating}，见 {@link #resolveSortPath()} 的说明 */
    private Integer minRating;

    /** 评分上限（含） */
    private Integer maxRating;

    /** 阅读人数下限（含） */
    private Integer minReadingCount;

    /** 按采集来源筛：CATEGORY / RANKING */
    private String targetType;

    /** 按采集来源筛：目标 ID */
    private String targetId;

    /** 作者，精确匹配 */
    private String author;

    /**
     * 出版时间下限，形如 {@code "2020"} 或 {@code "2020-06"}。
     *
     * <p>用<b>字符串比较</b>而不是日期解析，因为 {@code publishTime} 在库里就是
     * 字符串（{@code "2022-08-01 00:00:00"}，见 {@code Book.publishTime}），
     * 且它的格式是定长零填充的，字典序恰好等于时间序 —— 可以直接用
     * {@code $gte} / {@code $lte} 做区间。把字符串转成 Date 反而会引入
     * 「有的记录没有秒、有的格式不规范」这类解析失败，收益为零。
     */
    private String minPublishTime;

    /** 出版时间上限，形如 {@code "2023-12"}。传 {@code "2023"} 时语义是「2023 年全年」，见 resolveMaxPublishTime() */
    private String maxPublishTime;

    /** 排序字段。可选值见 {@link #resolveSortPath()}，非法值回退到评分 */
    private String sortField = "newRating";

    /** 是否升序。默认降序（高分/多人的在前） */
    private boolean asc = false;

    /** 页码，从 1 开始 */
    private int page = 1;

    /** 每页条数 */
    private int size = DEFAULT_SIZE;

    public BookQuery() {
    }

    // ------------------------------------------------------------------
    // 归一化：外部传什么脏值都不能让查询出错
    // ------------------------------------------------------------------

    /** 页码至少为 1 */
    public int getSafePage() {
        return page < 1 ? 1 : page;
    }

    /** 每页条数夹在 [1, MAX_SIZE] */
    public int getSafeSize() {
        if (size < 1) {
            return DEFAULT_SIZE;
        }
        return size > MAX_SIZE ? MAX_SIZE : size;
    }

    /**
     * 把前端的排序字段名翻译成 Mongo 的字段路径。
     *
     * <p><b>白名单机制</b>：不合法的一律回退到评分排序。绝不能把用户传的字符串
     * 直接拼进 Mongo 路径 —— 那是注入面。
     *
     * <h3>🔴 S4 修正：{@code newRating} 的路径是<b>顶层</b> {@code newRating}</h3>
     * 这里原来写的是 {@code "newRatingDetail.newRating"}，是错的。
     * {@code newRatingDetail} 的实际结构是 {@code {good, fair, poor, recent, title}}
     * —— 它里面<b>没有</b> {@code newRating} 这个键（已用 {@code books.json} 的
     * 20 本书样本逐字段核对，并对着库里 140 条真实数据验证）。
     * 推荐值 0~1000 是<b>顶层</b>的 {@link com.bookcollector.book.entity.Book#newRating}。
     *
     * <p>后果是静默的：排序指向不存在的字段不会报错，只是「排了跟没排一样」；
     * 而筛选（{@link #minRating} / {@link #maxRating}）会<b>永远返回 0 条</b>。
     * 页面不报错、只是空列表 —— 这种缺陷在 S1/S2 的测试里照不出来，
     * 因为那些测试验的是「解析对不对」「索引建没建」，没人验过「筛选真的筛得动吗」。
     * S4 做仪表盘评分分布时，发现柱图全是 0 才牵出来。
     */
    public String resolveSortPath() {
        if (sortField == null) {
            return "newRating";
        }
        switch (sortField) {
            case "readingCount":
                return "readingCount";
            case "newRatingCount":
                return "newRatingCount";
            case "lastCollectedAt":
                return "lastCollectedAt";
            case "publishTime":
                return "publishTime";
            case "newRating":
            default:
                return "newRating";
        }
    }

    /** 采集来源的复合键，形如 "category:300000"。两者缺一就返回 null（表示不筛来源） */
    public String resolveSourceKey() {
        if (targetType == null || targetType.trim().isEmpty()
                || targetId == null || targetId.trim().isEmpty()) {
            return null;
        }
        return targetType.trim().toLowerCase() + ":" + targetId.trim();
    }

    /** 作者精确匹配值。空白串归一成 null（「没填」而不是「找作者是空字符串的书」） */
    public String resolveAuthor() {
        return blankToNull(author);
    }

    /** 出版时间下限。空白串归一成 null */
    public String resolveMinPublishTime() {
        return blankToNull(minPublishTime);
    }

    /**
     * 出版时间上限，把「粗粒度」的输入补齐成可直接做字符串比较的形式。
     *
     * <p>为什么要补：用户填 {@code "2023"} 想表达的是「2023 年全年」，
     * 但 {@code "2023"} 做 {@code $lte} 会把 {@code "2023-08-01 00:00:00"}
     * 排除掉（因为 {@code "2023" < "2023-08-01"}）。补齐后
     * {@code "2023-12-31 23:59:59"} 就正确覆盖了全年。
     *
     * <table border="1">
     *   <tr><th>输入</th><th>补成</th><th>含义</th></tr>
     *   <tr><td>{@code 2023}</td><td>{@code 2023-12-31 23:59:59}</td><td>2023 全年</td></tr>
     *   <tr><td>{@code 2023-06}</td><td>{@code 2023-06-31 23:59:59}</td><td>2023 年 6 月</td></tr>
     *   <tr><td>其它</td><td>原样</td><td>已经是完整时间，不再猜</td></tr>
     * </table>
     *
     * <p>注意 {@code 2023-06-31} 是个不存在的日期 —— 但这没关系，我们做的是
     * <b>字符串</b>比较不是日期比较，{@code "-31 23:59:59"} 只起「该月最大值」
     * 的作用，不会被解析成日期。
     */
    public String resolveMaxPublishTime() {
        String v = blankToNull(maxPublishTime);
        if (v == null) {
            return null;
        }
        if (v.length() == 4) {
            return v + "-12-31 23:59:59";
        }
        if (v.length() == 7) {
            return v + "-31 23:59:59";
        }
        return v;
    }

    private String blankToNull(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        return s.trim();
    }

    // ------------------------------------------------------------------
    // getter / setter
    // ------------------------------------------------------------------

    public Integer getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Integer categoryId) {
        this.categoryId = categoryId;
    }

    public Integer getMinRating() {
        return minRating;
    }

    public void setMinRating(Integer minRating) {
        this.minRating = minRating;
    }

    public Integer getMaxRating() {
        return maxRating;
    }

    public void setMaxRating(Integer maxRating) {
        this.maxRating = maxRating;
    }

    public Integer getMinReadingCount() {
        return minReadingCount;
    }

    public void setMinReadingCount(Integer minReadingCount) {
        this.minReadingCount = minReadingCount;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getMinPublishTime() {
        return minPublishTime;
    }

    public void setMinPublishTime(String minPublishTime) {
        this.minPublishTime = minPublishTime;
    }

    public String getMaxPublishTime() {
        return maxPublishTime;
    }

    public void setMaxPublishTime(String maxPublishTime) {
        this.maxPublishTime = maxPublishTime;
    }

    public String getSortField() {
        return sortField;
    }

    public void setSortField(String sortField) {
        this.sortField = sortField;
    }

    public boolean isAsc() {
        return asc;
    }

    public void setAsc(boolean asc) {
        this.asc = asc;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }
}
