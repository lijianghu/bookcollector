package com.bookcollector.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 索引初始化器 —— 显式创建全部索引。
 *
 * <h3>为什么不用 {@code @Indexed} 注解自动建</h3>
 * {@code application.yml} 里设了 {@code auto-index-creation: false}。原因有二：
 * <ol>
 *   <li>自动建索引的行为<b>不透明</b>——出问题时你不知道它到底建了没有、建成了什么样</li>
 *   <li>唯一索引失败（比如库里已有重复数据）时，自动建索引的报错往往被吞掉</li>
 * </ol>
 * 显式创建的好处是：建了哪些、叫什么名字、失败没有，全在启动日志里。
 *
 * <h3>顺带解决「集合不存在」的问题</h3>
 * 在一个不存在的集合上建索引，MongoDB 会<b>先把集合建出来</b>。
 * 所以这个初始化器跑完，8 个集合就一定都存在了 —— 不需要单独写建集合的逻辑。
 *
 * <h3>⚠️ R22：{@code uk_bookId} 不是性能优化</h3>
 * 它是<b>幂等写入的正确性依赖</b>。采集是「按 bookId upsert」，如果这个唯一索引缺失，
 * 并发或重试场景下同一本书会插出两条，后续所有统计、分页都会受影响。
 * 所以它必须在第一次采集之前就位 —— 这就是 S1 排在 S2 前面的原因。
 *
 * <p>执行顺序：{@link Ordered#HIGHEST_PRECEDENCE}，早于 {@link SeedRunner}，
 * 保证 seed 写入时唯一索引已经在了。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MongoIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MongoIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public MongoIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("========== 开始初始化 MongoDB 索引 ==========");
        long start = System.currentTimeMillis();

        int created = 0;
        created += initBooks();
        created += initApiRequests();
        created += initCollectTasks();
        created += initCollectTaskRuns();
        created += initCollectTaskLogs();
        created += initCollectCursors();
        created += initTaxonomyConfig();
        created += initSettings();

        log.info("========== 索引初始化完成：{} 个索引，耗时 {} ms ==========",
                created, System.currentTimeMillis() - start);
    }

    /**
     * books —— 图书主表。
     *
     * <p>4 个索引，全部是查询/正确性刚需，没有为了「以后可能用」而提前建的。
     */
    private int initBooks() {
        IndexOperations ops = mongoTemplate.indexOps("books");

        // ★ R22：唯一索引。幂等 upsert 的正确性依赖，不是性能优化。
        ops.ensureIndex(new Index()
                .on("bookId", Sort.Direction.ASC)
                .unique()
                .named("uk_bookId"));

        // 按分类筛（「文学有哪些书」）
        ops.ensureIndex(new Index()
                .on("categories.categoryId", Sort.Direction.ASC)
                .named("idx_categories"));

        // 增量采集用（「最近一次采集是哪天」）
        ops.ensureIndex(new Index()
                .on("lastCollectedAt", Sort.Direction.ASC)
                .named("idx_lastCollectedAt"));

        // 按评分筛/排序。
        //
        // 🔴 S4 修正：字段是「顶层 newRating」（推荐值 0~1000），不是
        // newRatingDetail.newRating —— 后者不存在（newRatingDetail 只有
        // {good, fair, poor, recent, title}）。S1 建这个索引时字段路径写错了，
        // 于是索引建在一个所有文档都没有的字段上：既不报错，也完全没用。
        //
        // ⚠️ 因为索引名没变、字段变了，必须先把旧的删掉再建 ——
        // MongoDB 对同名索引的字段变更会直接报
        // 「Index with name: idx_newRating already exists with different options」。
        dropIndexIfFieldChanged(ops, "idx_newRating", "newRating");
        ops.ensureIndex(new Index()
                .on("newRating", Sort.Direction.ASC)
                .named("idx_newRating"));

        // 仪表盘用：「今日新增」「近 7 天采集量」都是按 firstCollectedAt 数新增
        // （口径见 StatsService 类注释 —— 用 firstCollectedAt 而不是 lastCollectedAt，
        //   因为后者每次 upsert 都刷新，重采同一批书会虚增）。
        // 注意这与 idx_lastCollectedAt 不是重复索引：两者服务于完全不同的查询
        // （「最近采过什么」 vs 「每天新增了多少」），字段不同，不能互相替代。
        ops.ensureIndex(new Index()
                .on("firstCollectedAt", Sort.Direction.ASC)
                .named("idx_firstCollectedAt"));

        return 5;
    }

    /**
     * 如果同名索引建在了别的字段上，先删掉它。
     *
     * <h3>为什么需要这个「自愈」动作</h3>
     * {@code ensureIndex} 的语义是「按名字 upsert」：同名索引已存在时它<b>什么都不做</b>，
     * 不会去比较字段。所以一旦字段路径写错过一次（本项目的 {@code idx_newRating}
     * 就写错过），改代码<b>不会</b>修好已经建出来的索引 —— 它会一直是个无用的索引，
     * 而且没有任何提示。加了这段之后，重启服务就自动纠正。
     *
     * <p>代价是每次启动多一次 {@code listIndexes}（毫秒级），换来的是
     * 「索引定义和代码不可能长期不一致」。
     *
     * @param expectedFirstField 期望的第一个索引字段。只有「单字段索引」才做校正，
     *                           复合索引的字段顺序语义复杂，不做自动判断
     */
    private void dropIndexIfFieldChanged(IndexOperations ops, String indexName, String expectedFirstField) {
        try {
            for (IndexInfo info : ops.getIndexInfo()) {
                if (!indexName.equals(info.getName())) {
                    continue;
                }
                List<IndexField> fields = info.getIndexFields();
                boolean sameField = fields.size() == 1
                        && expectedFirstField.equals(fields.get(0).getKey());
                if (sameField) {
                    return;
                }
                ops.dropIndex(indexName);
                log.warn("索引 {} 建在错误的字段上（实际 {}，期望 {}），已删除，将按正确字段重建",
                        indexName, describeFields(fields), expectedFirstField);
                return;
            }
        } catch (Exception e) {
            // 校正失败不该拦住启动 —— ensureIndex 后面还会跑一次，
            // 最坏情况就是维持现状（与加这段之前的行为一致）
            log.warn("检查索引 {} 的字段时出错，跳过校正：{}", indexName, e.getMessage());
        }
    }

    private String describeFields(List<IndexField> fields) {
        StringBuilder sb = new StringBuilder();
        for (IndexField f : fields) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(f.getKey());
        }
        return sb.toString();
    }

    /** api_requests —— 写入量大，索引克制，只建真正会查的两个 */
    private int initApiRequests() {
        IndexOperations ops = mongoTemplate.indexOps("api_requests");

        // 「最近有哪些请求失败」——按时间倒序翻
        ops.ensureIndex(new Index()
                .on("createdAt", Sort.Direction.DESC)
                .named("idx_createdAt"));

        // 「某次运行打了哪些请求」
        ops.ensureIndex(new Index()
                .on("runId", Sort.Direction.ASC)
                .named("idx_runId"));

        return 2;
    }

    private int initCollectTasks() {
        IndexOperations ops = mongoTemplate.indexOps("collect_tasks");

        // ★ 一个目标只能有一个任务。
        //
        // 这不是「防重复提交」那种可有可无的约束，而是游标模型的一致性要求：
        // collect_cursors 的主键是 (targetType, targetId)，即「游标是目标的属性，
        // 不是任务的属性」（见 CollectCursor 类注释）。如果允许两个任务采同一个目标，
        // 它们会共享同一个游标 —— A 采到第 100 页后，B 启动时会从第 100 页继续而不是从 0，
        // 而界面上看不出任何异常。这种「静默的意外行为」比直接报错难排查得多。
        //
        // ⚠️ S1 阶段漏建了这个索引，S3 写 TaskService.create() 时才发现
        // （单元测试「同一目标不能建两个任务」没有抛异常）。补建时若库里已有重复的
        // (targetType, targetId)，启动会失败 —— 那时先手工去重：
        //   db.collect_tasks.aggregate([{$group:{_id:{t:"$targetType",i:"$targetId"},n:{$sum:1}}},
        //                               {$match:{n:{$gt:1}}}])
        ops.ensureIndex(new Index()
                .on("targetType", Sort.Direction.ASC)
                .on("targetId", Sort.Direction.ASC)
                .unique()
                .named("uk_target"));

        ops.ensureIndex(new Index()
                .on("status", Sort.Direction.ASC)
                .named("idx_status"));

        ops.ensureIndex(new Index()
                .on("createdAt", Sort.Direction.DESC)
                .named("idx_createdAt"));

        return 3;
    }

    private int initCollectTaskRuns() {
        IndexOperations ops = mongoTemplate.indexOps("collect_task_runs");

        // 「某任务的历史运行记录」
        ops.ensureIndex(new Index()
                .on("taskId", Sort.Direction.ASC)
                .named("idx_taskId"));

        // 「最近跑了什么」+ 「有没有卡死的任务」
        ops.ensureIndex(new Index()
                .on("startedAt", Sort.Direction.DESC)
                .named("idx_startedAt"));

        return 2;
    }

    private int initCollectTaskLogs() {
        IndexOperations ops = mongoTemplate.indexOps("collect_task_logs");

        // 日志永远按 runId 查，不按时间全表翻
        ops.ensureIndex(new Index()
                .on("runId", Sort.Direction.ASC)
                .named("idx_runId"));

        return 1;
    }

    private int initCollectCursors() {
        IndexOperations ops = mongoTemplate.indexOps("collect_cursors");

        // 一个 target 只能有一行游标，否则断点续传会读到不确定的值
        ops.ensureIndex(new Index()
                .on("targetType", Sort.Direction.ASC)
                .on("targetId", Sort.Direction.ASC)
                .unique()
                .named("uk_target"));

        return 1;
    }

    private int initTaxonomyConfig() {
        IndexOperations ops = mongoTemplate.indexOps("taxonomy_config");

        // 同一个 (type, code) 只能有一条，seed 幂等的依赖
        ops.ensureIndex(new Index()
                .on("type", Sort.Direction.ASC)
                .on("code", Sort.Direction.ASC)
                .unique()
                .named("uk_type_code"));

        // 前端下拉框按 sort 排
        ops.ensureIndex(new Index()
                .on("sort", Sort.Direction.ASC)
                .named("idx_sort"));

        return 2;
    }

    private int initSettings() {
        IndexOperations ops = mongoTemplate.indexOps("settings");

        ops.ensureIndex(new Index()
                .on("key", Sort.Direction.ASC)
                .unique()
                .named("uk_key"));

        return 1;
    }
}
