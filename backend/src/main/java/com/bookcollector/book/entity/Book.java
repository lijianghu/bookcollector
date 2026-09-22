package com.bookcollector.book.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 图书文档（原 MySQL 的 {@code books} + {@code book_categories} + {@code book_rating_details} 三表合一）。
 *
 * <p><b>为什么合并</b>：原 Python 保存一本书要 5~6 次非原子写（books 插/更新、
 * book_categories 先删后插、book_rating_details 先删后插）。合并成单文档后变成
 * <b>1 次原子写入</b>，天然幂等，也不会出现「图书存了但分类没存」的中间态。
 *
 * <p><b>字段口径</b>：下面 <b>46 个业务字段</b>与 {@code book_api_client.py} 的 {@code BookInfo}
 * 一一对应，命名按 Java 驼峰（原 SQL 的下划线命名见 {@code database_schema.sql}）。
 * 特别注意 3 个取自「外层记录」而不是 {@code bookInfo} 的字段：
 * <ul>
 *   <li>{@link #searchIdx} ← 外层 {@code searchIdx} —— 分页游标就靠它</li>
 *   <li>{@link #typeInfo} ← 外层 {@code type} —— <b>不是</b> bookInfo 里的 type</li>
 *   <li>{@link #readingCount} ← 外层 {@code readingCount}</li>
 * </ul>
 * 另有 2 个字段是从嵌套对象「拍平」出来的：{@code paperBook.skuId} → {@link #paperBookSkuId}，
 * {@code newRatingDetail.title} → {@link #newRatingTitle}。
 *
 * <p><b>数值口径不做换算</b>：{@code newRating} 微信读书用 0~1000 的推荐值（913 = 91.3%），
 * 原 Python 直接存 913，这里同样存 913。展示层要显示百分比时自己除 10。
 *
 * <p><b>索引不在本类声明</b>：{@code application.yml} 里 {@code auto-index-creation: false}，
 * 所有索引由 {@code com.bookcollector.config.MongoIndexInitializer} 显式创建。
 * 唯一索引不是性能优化，是幂等写入的正确性依赖（风险 R22）。
 *
 * @see com.bookcollector.config.MongoIndexInitializer
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "books")
public class Book implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Mongo 主键（ObjectId 的字符串形式） */
    @Id
    private String id;

    // ==================================================================
    // ↓↓↓ 46 个业务字段 —— 与 BookInfo 一一对应 ↓↓↓
    // ==================================================================

    /** 图书 ID。★ 唯一索引 uk_bookId 建在这个字段上 */
    private String bookId;

    /** 书名 */
    private String title;

    /** 作者 */
    private String author;

    /** 译者。可选字段，实测约 7/20 的记录才有 */
    private String translator;

    /** 封面 URL */
    private String cover;

    /** 版本号。原 SQL 是 BIGINT，用 Long 防止将来变成毫秒时间戳时溢出 */
    private Long version;

    /** 格式，如 epub */
    private String format;

    /** 类型（bookInfo.type） */
    private Integer type;

    /** 价格（元） */
    private Integer price;

    /** 原价（元） */
    private Integer originalPrice;

    /** 是否售罄 */
    private Integer soldout;

    /** 图书状态 */
    private Integer bookStatus;

    /** 付费状态 */
    private Integer payingStatus;

    /** 付费类型（位掩码，如 1048577）。原 SQL 是 BIGINT */
    private Long payType;

    /** 图书简介。原文较长（含 [1] 这类脚注标记），保持原样 */
    private String intro;

    /** 价格（分），如 3500 对应 price=35 */
    private Integer centPrice;

    /** 是否完结 */
    private Integer finished;

    /** 最大免费章节序号 */
    private Integer maxFreeChapter;

    /** 是否免费 */
    private Integer free;

    /** 会员卡折扣 */
    private Integer mcardDiscount;

    /** 是否出版 */
    private Integer ispub;

    /** 额外类型 */
    private Integer extraType;

    /** CP ID，可为负数 */
    private Long cpid;

    /** 出版时间，形如 "2022-08-01 00:00:00"（保持字符串，不做 Date 解析） */
    private String publishTime;

    /** 分类的字符串形式，如 "精品小说-社会小说"。等价于 categories[0].title */
    private String category;

    /** 平台给这本书打的分类标签（原 book_categories 表）。注意：这是「平台分类」，不是我们的采集来源 */
    private List<BookCategory> categories;

    /** 是否有讲座 */
    private Integer hasLecture;

    /** 最后章节索引 */
    private Integer lastChapterIdx;

    /** 纸质书 SKU ID（由 paperBook.skuId 拍平而来） */
    private String paperBookSkuId;

    /** 是否阻止保存图片 */
    private Integer blockSaveImg;

    /** 语言，如 "zh-wr" */
    private String language;

    /** 是否繁体中文。API 返回真布尔值，不是 0/1 */
    private Boolean isTraditionalChinese;

    /** 是否隐藏更新时间。API 返回真布尔值 */
    private Boolean hideUpdateTime;

    /** 是否 EPUB 漫画 */
    private Integer isEpubComics;

    /** 是否竖排布局 */
    private Integer isVerticalLayout;

    /** 是否显示 TTS */
    private Integer isShowTts;

    /** 网页图书控制 */
    private Integer webBookControl;

    /** 自制激励。API 返回真布尔值 */
    private Boolean selfProduceIncentive;

    /** 是否自动下载 */
    private Integer isAutoDownload;

    /** 推荐值 0~1000（913 = 91.3%），不做换算 */
    private Integer newRating;

    /** 评价人数 */
    private Integer newRatingCount;

    /** 评价标题，如 "神作"（由 newRatingDetail.title 拍平而来） */
    private String newRatingTitle;

    /** ★ 分页游标来源：外层 searchIdx。下一页 maxIndex 取本页最后一条的 searchIdx */
    private Integer searchIdx;

    /** 外层 type。★ 别和 {@link #type}（bookInfo.type）搞混 */
    private Integer typeInfo;

    /** 外层 readingCount（阅读人数） */
    private Integer readingCount;

    /** 评分分布（原 book_rating_details 表） */
    private RatingDetail newRatingDetail;

    // ==================================================================
    // ↓↓↓ 以下为「API 会返回、但原 Python 未映射」的字段 ↓↓↓
    // 原 BookInfo 里没有这 4 个，等于白白丢弃了。
    // Mongo 无 schema，存下来的边际成本≈0；而一旦将来要用，
    // 重采一次的代价是 ~40 分钟/分类 × 21 个分类。所以这里保留。
    // ==================================================================

    /** 免费章节详情（原 API 的 maxFreeInfo） */
    private MaxFreeInfo maxFreeInfo;

    /** 有版权限制的章节 UID 列表 */
    private List<Integer> copyrightChapterUids;

    /** 推荐语（推广文案），实测仅 3/20 记录有 */
    private String lPushName;

    /** 作者 ID，实测仅 1/20 记录有 */
    private String authorVids;

    // ==================================================================
    // ↓↓↓ 采集元数据（原 MySQL 没有，是本次改造新增的） ↓↓↓
    // ==================================================================

    /** 首次采集时间。批量 upsert 时用 $setOnInsert 写入，之后不再改 */
    private Date firstCollectedAt;

    /** 最近一次采集时间。每次 upsert 都用 $set 覆盖 */
    private Date lastCollectedAt;

    /**
     * 采集来源列表，形如 {@code ["category:300000", "ranking:rising"]}。
     * 同一本书可能既属于某分类又在某榜单里，用 {@code $addToSet} 累加，不会重复。
     */
    private List<String> collectSource;

    // ==================================================================
    // 嵌套结构
    // ==================================================================

    /** 平台分类标签。对应 API 的 {@code bookInfo.categories[]} */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BookCategory implements Serializable {

        private static final long serialVersionUID = 1L;

        private Integer categoryId;
        private Integer subCategoryId;
        private Integer categoryType;
        /** 形如 "文学-经典作品" */
        private String title;
    }

    /** 评分分布。对应 API 的 {@code bookInfo.newRatingDetail} */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RatingDetail implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 好评数 */
        private Integer good;
        /** 一般数 */
        private Integer fair;
        /** 差评数 */
        private Integer poor;
        /** 近期评价数 */
        private Integer recent;
        /** 评价档位文案，如 "神作" */
        private String title;
    }

    /** 免费章节详情。对应 API 的 {@code bookInfo.maxFreeInfo} */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MaxFreeInfo implements Serializable {

        private static final long serialVersionUID = 1L;

        private Integer maxFreeChapterIdx;
        private Integer maxFreeChapterUid;
        /** 免费比例（百分数），如 79 */
        private Integer maxFreeChapterRatio;
    }
}
