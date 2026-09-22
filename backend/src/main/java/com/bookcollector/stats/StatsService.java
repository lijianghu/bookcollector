package com.bookcollector.stats;

import com.bookcollector.audit.entity.ApiRequest;
import com.bookcollector.book.entity.Book;
import com.bookcollector.common.enums.TargetType;
import com.bookcollector.cursor.entity.CollectCursor;
import com.bookcollector.task.TaskRegistry;
import com.bookcollector.task.entity.CollectTask;
import com.bookcollector.taxonomy.TaxonomyRepository;
import com.bookcollector.taxonomy.entity.TaxonomyItem;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperationContext;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仪表盘统计（FR-F1 / FR-F2）。
 *
 * <h3>为什么用聚合管道而不是「查出全部再在 Java 里算」</h3>
 * 图书量预期 1 万–5 万条。把 5 万份文档（每份 46 个字段）拉到 JVM 里只为数几个桶，
 * 是纯粹的浪费：网络传输、反序列化、GC 全都白干。聚合在 MongoDB 里跑，
 * 回来的只有几十行结果。
 *
 * <h3>为什么直接写 {@code Document} 而不是用 {@code Aggregation.group(...)} 链式 API</h3>
 * 本类用到的 {@code $dateToString}、{@code $split}、{@code $arrayElemAt}、{@code $cond}
 * 这类「表达式」，用 Spring Data 的类型化 API 写出来比原生管道<b>更长、更难读</b>，
 * 而且字段名要靠字符串拼（该错还是会错）。直接用 {@code Document} 写管道，
 * 复制到 {@code mongosh} 里就能单独调试 —— 这个可验证性值很高。
 *
 * <h3>🔴 「今日采集数」的口径：按 {@code firstCollectedAt}（净新增）</h3>
 * 不按 {@code lastCollectedAt}。理由：{@code lastCollectedAt} 每次 upsert 都会刷新，
 * 重采同一批书会让它「今天又采了一万本」，而实际一本都没新增。
 * 用 {@code firstCollectedAt} 得到的是「今天库里多了多少本之前没有的书」——
 * 这才是用户看到这个数字时的预期。图表「近 7 天采集量」用同一个口径，
 * 保证指标卡和图表不会互相矛盾。
 */
@Service
public class StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsService.class);

    /** 全链路时区（对齐 2.5 非功能需求） */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 近 N 天 */
    private static final int RECENT_DAYS = 7;

    /** 评分分桶：每 100 分一个桶，0~1000 共 10 桶 */
    private static final int RATING_BUCKET_SIZE = 100;

    private static final int RATING_BUCKET_COUNT = 10;

    /** 分类分布最多返回多少个扇区。饼图超过 30 片就没法看了 */
    private static final int DISTRIBUTION_LIMIT = 30;

    private final MongoTemplate mongoTemplate;
    private final TaxonomyRepository taxonomyRepository;
    private final TaskRegistry taskRegistry;

    public StatsService(MongoTemplate mongoTemplate,
                        TaxonomyRepository taxonomyRepository,
                        TaskRegistry taskRegistry) {
        this.mongoTemplate = mongoTemplate;
        this.taxonomyRepository = taxonomyRepository;
        this.taskRegistry = taskRegistry;
    }

    // ==================================================================
    // FR-F1：4 个指标卡
    // ==================================================================

    /**
     * 指标卡。
     *
     * <p>4 个主指标是 {@code bookTotal} / {@code categoryTotal} / {@code taskTotal} /
     * {@code todayCollected}。其余字段是顺手带上的上下文 —— 它们都是「打开仪表盘时
     * 想知道但不想再发一次请求」的信息（有没有任务在跑、最近一次采集是什么时候）。
     */
    public Map<String, Object> overview() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();

        // --- 4 个主指标 ---
        data.put("bookTotal", mongoTemplate.count(new Query(), Book.class));
        data.put("categoryTotal", taxonomyRepository.countByType(TargetType.CATEGORY));
        data.put("taskTotal", mongoTemplate.count(new Query(), CollectTask.class));
        data.put("todayCollected", countCollectedSince(startOfToday()));

        // --- 上下文 ---
        data.put("categoryEnabled", taxonomyRepository.countByTypeAndEnabled(TargetType.CATEGORY, true));
        data.put("rankingTotal", taxonomyRepository.countByType(TargetType.RANKING));
        data.put("rankingEnabled", taxonomyRepository.countByTypeAndEnabled(TargetType.RANKING, true));
        data.put("taskRunning", countTasksByStatus(true));
        data.put("activeRuns", taskRegistry.activeCount());
        data.put("cursorTotal", mongoTemplate.count(new Query(), CollectCursor.class));
        data.put("requestTotal", mongoTemplate.count(new Query(), ApiRequest.class));
        data.put("last7DaysCollected", countCollectedSince(startOfDaysAgo(RECENT_DAYS - 1)));
        data.put("lastCollectedAt", maxLastCollectedAt());
        return data;
    }

    // ==================================================================
    // FR-F2：4 张图
    // ==================================================================

    public Map<String, Object> charts() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("categoryDistribution", categoryDistribution());
        data.put("ratingDistribution", ratingDistribution());
        data.put("publishYearTrend", publishYearTrend());
        data.put("recentCollected", recentCollected());
        return data;
    }

    /**
     * 分类分布（饼图）。
     *
     * <h3>口径说明：按「采集目标」分，不按「平台分类」分</h3>
     * 每本书的 {@code collectSource} 是 {@code ["category:300000", "ranking:rising"]}
     * 这样的来源列表 —— 展开后按来源计数，就是「21 个分类 + 7 个榜单各贡献了多少本书」。
     *
     * <p>为什么不用平台给的 {@code category}（如「精品小说-社会小说」）：
     * 那个字段的取值有几百个，饼图会碎成一片糊；而且它反映的是<b>微信读书</b>的分类体系，
     * 不是「我这个采集器采了什么」。本系统的核心叙事就是「我采了哪些目标、各多少本」，
     * 所以按采集目标分才是这张图该回答的问题。
     *
     * <p>一个副作用是：同一本书如果既在分类里又在榜单里，会被<b>计两次</b>
     * （各来源一次）。这是对的 —— 两个扇区的和会大于图书总数，因为维度是
     * 「来源贡献量」而不是「图书不重复归属」。
     */
    private List<Map<String, Object>> categoryDistribution() {
        List<Document> pipeline = Arrays.asList(
                new Document("$match", new Document("collectSource",
                        new Document("$exists", true).append("$ne", new ArrayList<String>()))),
                new Document("$unwind", "$collectSource"),
                new Document("$group", new Document("_id", "$collectSource")
                        .append("count", new Document("$sum", 1))),
                new Document("$sort", new Document("count", -1)),
                new Document("$limit", DISTRIBUTION_LIMIT)
        );
        List<Document> rows = aggregate(pipeline, "books");

        // 把 "category:300000" 翻成「文学」。字典只有 28 条，一次全查出来做内存映射，
        // 比在管道里 $lookup 一次更省事，也更容易排查（映射不上的会落到 code 上）
        Map<String, String> nameByKey = taxonomyNameMap();

        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Document row : rows) {
            String sourceKey = row.getString("_id");
            long count = toLong(row.get("count"));

            String[] parts = splitSourceKey(sourceKey);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("sourceKey", sourceKey);
            item.put("type", parts[0]);
            item.put("targetId", parts[1]);
            // 字典里查不到就用 code 本身兜底 —— 不隐藏异常数据，让人能看见它
            item.put("name", nameByKey.getOrDefault(parts[0] + "|" + parts[1], sourceKey));
            item.put("count", count);
            result.add(item);
        }
        return result;
    }

    /**
     * 评分分布（柱图）。
     *
     * <p><b>🔴 S4 修正</b>：用<b>顶层</b> {@code newRating}（推荐值 0~1000），
     * 不是 {@code newRatingDetail.newRating} —— 后者<b>不存在</b>
     * （{@code newRatingDetail} 只有 {@code {good, fair, poor, recent, title}}）。
     * 一开始这里写的是 {@code newRatingDetail.newRating}，柱图出来全是 0，
     * 顺着查才发现 {@code BookQuery} 的评分筛选和默认排序也踩了同一个坑
     * （详见 {@code BookQuery#resolveSortPath()} 的说明）。
     *
     * <p>这个坑之所以能藏到 S4，是因为它<b>不报错</b>：{@code $match} 匹配不到任何
     * 文档，管道正常返回空结果，聚合也不抛异常。
     *
     * <p>分桶用 {@code $cond} + {@code $floor} 而不是 {@code $bucket}：
     * {@code $bucket} 的边界数组必须严格递增、且恰好等于上边界的值会落到下一个桶
     * （1000 会掉出去），要额外加一个 1001 的边界才能覆盖。{@code $cond} 一眼就能看出
     * 「≥1000 归到最后一桶」，不用去查 {@code $bucket} 的边界语义。
     */
    private List<Map<String, Object>> ratingDistribution() {
        Document floorDiv = new Document("$floor", new Document("$divide",
                Arrays.asList("$newRating", RATING_BUCKET_SIZE)));
        Document bucketExpr = new Document("$cond", Arrays.asList(
                new Document("$gte", Arrays.asList("$newRating",
                        RATING_BUCKET_SIZE * RATING_BUCKET_COUNT)),
                RATING_BUCKET_COUNT - 1,
                floorDiv));

        List<Document> pipeline = Arrays.asList(
                new Document("$match", new Document("newRating",
                        new Document("$gte", 0))),
                new Document("$project", new Document("bucket", bucketExpr)),
                new Document("$group", new Document("_id", "$bucket")
                        .append("count", new Document("$sum", 1))),
                new Document("$sort", new Document("_id", 1))
        );
        List<Document> rows = aggregate(pipeline, "books");

        Map<Integer, Long> countByBucket = new HashMap<Integer, Long>();
        for (Document row : rows) {
            Object id = row.get("_id");
            if (id instanceof Number) {
                countByBucket.put(((Number) id).intValue(), toLong(row.get("count")));
            }
        }

        // 补齐空桶：没有书的评分区间也要出现在横轴上，否则柱图的 X 轴会「缺格」，
        // 让人误以为分布是连续的
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < RATING_BUCKET_COUNT; i++) {
            int min = i * RATING_BUCKET_SIZE;
            int max = min + RATING_BUCKET_SIZE;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("label", min + "-" + max);
            item.put("min", min);
            item.put("max", max);
            item.put("count", countByBucket.getOrDefault(i, 0L));
            result.add(item);
        }
        return result;
    }

    /**
     * 出版年份趋势（折线图）。
     *
     * <h3>为什么用 {@code $split} 取年份而不是 {@code $substrBytes}</h3>
     * {@code publishTime} 是字符串（{@code "2022-08-01 00:00:00"}）。取前 4 位做年份，
     * 直觉上该用 {@code $substrBytes: ["$publishTime", 0, 4]} —— 但它在字符串
     * 短于 4 个字符时的行为依赖版本（有的报错、有的补空），而库里确实存在
     * {@code "2022"} 这种只有年份的脏值。{@code $split} + {@code $arrayElemAt}
     * 对任意长度都安全，而且语义就是「取第一段」。
     *
     * <p>年份范围限制在 1900–2100：库里存在 {@code "0000-00-00 00:00:00"}
     * 这类「未知时间」的占位值，不过滤的话折线图上会出现一个「0000 年」的尖峰。
     */
    private List<Map<String, Object>> publishYearTrend() {
        List<Document> pipeline = Arrays.asList(
                new Document("$match", new Document("publishTime",
                        new Document("$type", "string"))),
                new Document("$project", new Document("year",
                        new Document("$arrayElemAt",
                                Arrays.asList(new Document("$split", Arrays.asList("$publishTime", "-")), 0)))),
                new Document("$match", new Document("$and", Arrays.asList(
                        new Document("year", new Document("$regex", "^[0-9]{4}$")),
                        new Document("year", new Document("$gte", "1900").append("$lte", "2100"))))),
                new Document("$group", new Document("_id", "$year")
                        .append("count", new Document("$sum", 1))),
                new Document("$sort", new Document("_id", 1))
        );
        List<Document> rows = aggregate(pipeline, "books");

        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Document row : rows) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("year", row.getString("_id"));
            item.put("count", toLong(row.get("count")));
            result.add(item);
        }
        return result;
    }

    /**
     * 近 7 天采集量（柱图）。口径与指标卡 {@code todayCollected} 一致：按
     * {@code firstCollectedAt} 数「新增」。
     *
     * <p><b>必须补齐没有数据的日期</b>：管道只会返回「有书的日子」。
     * 如果直接把它交给 ECharts，一张 7 天的柱图可能只有 3 根柱子 ——
     * 横轴不是时间轴而是「有数据的日子」，视觉上会让人以为那几天之间没有间隔。
     */
    private List<Map<String, Object>> recentCollected() {
        Date since = startOfDaysAgo(RECENT_DAYS - 1);
        List<Document> pipeline = Arrays.asList(
                new Document("$match", new Document("firstCollectedAt",
                        new Document("$gte", since))),
                new Document("$group", new Document("_id",
                        new Document("$dateToString", new Document("format", "%Y-%m-%d")
                                .append("date", "$firstCollectedAt")
                                .append("timezone", ZONE.getId())))
                        .append("count", new Document("$sum", 1))),
                new Document("$sort", new Document("_id", 1))
        );
        List<Document> rows = aggregate(pipeline, "books");

        Map<String, Long> countByDay = new HashMap<String, Long>();
        for (Document row : rows) {
            countByDay.put(row.getString("_id"), toLong(row.get("count")));
        }

        LocalDate today = LocalDate.now(ZONE);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (int i = RECENT_DAYS - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            String key = day.format(DAY_FMT);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("date", key);
            item.put("count", countByDay.getOrDefault(key, 0L));
            result.add(item);
        }
        return result;
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /** 执行管道。输出用 {@code Document} 而不是实体 —— 结果是「聚合出来的形状」，没有对应实体 */
    private List<Document> aggregate(List<Document> pipeline, String collection) {
        AggregationOperation[] ops = new AggregationOperation[pipeline.size()];
        for (int i = 0; i < pipeline.size(); i++) {
            ops[i] = raw(pipeline.get(i));
        }
        try {
            return mongoTemplate.aggregate(Aggregation.newAggregation(ops), collection, Document.class)
                    .getMappedResults();
        } catch (RuntimeException e) {
            // 统计失败不该让仪表盘整个 500 —— 交给上层返回空数组，页面显示 0 比报错好
            log.error("聚合查询失败（collection={}）：{}", collection, e.getMessage());
            return new ArrayList<Document>();
        }
    }

    /**
     * 把原生 {@code Document} 包成 {@code AggregationOperation}。
     *
     * <p>直接返回原文档，不走 Spring Data 的字段名映射 —— 我们的字段名就是
     * Mongo 里的字段名（实体上没有 {@code @Field} 别名），映射只会多一层
     * 可能出错的转换。
     */
    private AggregationOperation raw(final Document doc) {
        return new AggregationOperation() {
            @Override
            public Document toDocument(AggregationOperationContext context) {
                return doc;
            }
        };
    }

    /** "category:300000" → ["CATEGORY", "300000"]；解析不出来时原样放回 */
    private String[] splitSourceKey(String sourceKey) {
        if (sourceKey == null) {
            return new String[]{"UNKNOWN", ""};
        }
        int idx = sourceKey.indexOf(':');
        if (idx <= 0 || idx == sourceKey.length() - 1) {
            return new String[]{"UNKNOWN", sourceKey};
        }
        String type = sourceKey.substring(0, idx).trim().toUpperCase();
        String id = sourceKey.substring(idx + 1).trim();
        return new String[]{type, id};
    }

    /** {@code "CATEGORY|300000" → "文学"} */
    private Map<String, String> taxonomyNameMap() {
        Map<String, String> map = new HashMap<String, String>();
        for (TaxonomyItem item : taxonomyRepository.findAll()) {
            if (item.getType() != null && item.getCode() != null && item.getName() != null) {
                map.put(item.getType().toUpperCase() + "|" + item.getCode(), item.getName());
            }
        }
        return map;
    }

    private long countCollectedSince(Date since) {
        return mongoTemplate.count(
                Query.query(Criteria.where("firstCollectedAt").gte(since)), Book.class);
    }

    private long countTasksByStatus(boolean active) {
        if (active) {
            return mongoTemplate.count(Query.query(Criteria.where("status")
                            .in(Arrays.asList("RUNNING", "PAUSED"))),
                    CollectTask.class);
        }
        return mongoTemplate.count(new Query(), CollectTask.class);
    }

    /** 最近一次采集时间。用聚合的 $max 而不是「排序取第一条」，少反序列化一整份文档 */
    private Date maxLastCollectedAt() {
        List<Document> rows = aggregate(Arrays.asList(
                new Document("$group", new Document("_id", null)
                        .append("max", new Document("$max", "$lastCollectedAt")))
        ), "books");
        if (rows.isEmpty()) {
            return null;
        }
        Object max = rows.get(0).get("max");
        return max instanceof Date ? (Date) max : null;
    }

    private Date startOfToday() {
        return startOfDaysAgo(0);
    }

    /** N 天前的 00:00:00（Asia/Shanghai）。N=0 就是今天零点 */
    private Date startOfDaysAgo(int days) {
        LocalDate day = LocalDate.now(ZONE).minusDays(days);
        return Date.from(day.atStartOfDay(ZONE).toInstant());
    }

    private long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }
}
