package com.bookcollector.book.repository;

import com.bookcollector.book.entity.Book;
import com.bookcollector.book.req.BookQuery;
import com.bookcollector.common.PageResult;
import com.bookcollector.util.PageQueryUtil;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 图书数据访问层（MongoTemplate 封装）。
 *
 * <h3>为什么不用 {@code MongoRepository<Book, String>}</h3>
 * 两个刚需 Spring Data 的 Repository 接口给不了：
 * <ol>
 *   <li><b>批量 upsert</b>。采集一页 20 本、一次任务上千本，逐条 {@code save()}
 *       就是上千次往返。{@code bulkOps} 可以合并成一次网络调用。</li>
 *   <li><b>幂等语义</b>。{@code save()} 是「有 _id 就替换、没有就插入」，
 *       而我们要的是「按 bookId 有则更新、无则插入」—— 这是 upsert，不是 save。</li>
 * </ol>
 *
 * @see com.bookcollector.config.MongoIndexInitializer
 */
@Repository
public class BookRepository {

    private static final Logger log = LoggerFactory.getLogger(BookRepository.class);

    /** {@code $in} 查询一次最多带多少个 bookId，避免 BSON 文档超过 16MB 上限 */
    private static final int IN_QUERY_CHUNK = 1000;

    private final MongoTemplate mongoTemplate;

    public BookRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // ==================================================================
    // 写
    // ==================================================================

    /**
     * 按 {@code bookId} 幂等批量 upsert。
     *
     * <p>这是整个采集链路的<b>唯一写入点</b>。语义：
     * <ul>
     *   <li>{@code bookId} 已存在 → 用新数据覆盖全部业务字段（以接口为真相），
     *       {@code firstCollectedAt} <b>保留原值</b></li>
     *   <li>{@code bookId} 不存在 → 插入，并写入 {@code firstCollectedAt}</li>
     *   <li>{@code collectSource} 用 {@code $addToSet} 累加，同一来源不会重复</li>
     * </ul>
     *
     * <p><b>⚠️ 这里为什么不靠 {@code @CreatedDate} / {@code @LastModifiedDate} 审计</b>：
     * {@code bulkOps().upsert(query, Update)} 操作的是 {@code Update} 对象而不是实体，
     * 没有实体就没有字段可让审计框架填。所以这两个时间字段在这里手工写。
     * 详见 {@link com.bookcollector.config.MongoConfig} 的说明。
     *
     * <p><b>幂等性依赖</b>：{@code uk_bookId} 唯一索引。没有它，并发或重试时
     * 同一本书会被插成两条（R22）。
     *
     * @param books     待写入的图书。{@code bookId} 为空/null 的会被跳过
     * @param sourceKey 采集来源，形如 {@code "category:300000"}。为 null 时不累加来源
     * @return 实际处理的条数（更新数 + 新增数）
     */
    public int upsertMany(List<Book> books, String sourceKey) {
        if (books == null || books.isEmpty()) {
            return 0;
        }

        Date now = new Date();
        BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, Book.class);

        int queued = 0;
        int skipped = 0;
        for (Book book : books) {
            if (book == null || book.getBookId() == null || book.getBookId().trim().isEmpty()) {
                skipped++;
                continue;
            }
            Query query = Query.query(Criteria.where("bookId").is(book.getBookId().trim()));
            bulk.upsert(query, buildUpsertUpdate(book, now, sourceKey));
            queued++;
        }

        if (skipped > 0) {
            log.warn("upsertMany 跳过 {} 条没有 bookId 的记录", skipped);
        }
        if (queued == 0) {
            return 0;
        }

        int upserted;
        int matched;
        try {
            com.mongodb.bulk.BulkWriteResult result = bulk.execute();
            matched = result.getMatchedCount();
            upserted = result.getUpserts().size();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 唯一索引冲突：说明同一批里出现了重复 bookId，或者有并发写入。
            // 这是数据问题不是程序 bug，抛出去让上层记日志 + 标 FAILED，不要吞。
            log.error("upsertMany 触发唯一索引冲突（bookId 重复）：{}", e.getMessage());
            throw e;
        }
        return matched + upserted;
    }

    /**
     * 构造 upsert 的 {@code Update}。
     *
     * <p>用「原始 Document」而不是 {@code Update.set(...)} 链式调用的原因：
     * 需要同时下发 {@code $set} + {@code $setOnInsert} + {@code $addToSet} 三个操作符，
     * 而且 {@code $set} 的内容是「实体全字段」—— 逐个字段手写 50 行
     * 既啰嗦又会在加字段时漏掉。直接用转换器把实体摊平成 Document 最省事。
     */
    private Update buildUpsertUpdate(Book book, Date now, String sourceKey) {
        Document setDoc = new Document();
        mongoTemplate.getConverter().write(book, setDoc);

        // _id 不能出现在 $set 里（MongoDB 会报 "Performing an update on the path '_id' would modify the immutable field '_id'"）
        setDoc.remove("_id");
        // 这三个字段不能用 $set，单独处理：
        //   firstCollectedAt → 只在插入时写，更新时必须保留原值
        //   lastCollectedAt  → 每次都要覆盖
        //   collectSource    → 用 $addToSet 累加，和 $set 冲突
        setDoc.remove("firstCollectedAt");
        setDoc.remove("lastCollectedAt");
        setDoc.remove("collectSource");
        setDoc.put("lastCollectedAt", now);

        Document updateDoc = new Document("$set", setDoc);
        updateDoc.put("$setOnInsert", new Document("firstCollectedAt", now));

        if (sourceKey != null && !sourceKey.trim().isEmpty()) {
            updateDoc.put("$addToSet", new Document("collectSource",
                    new Document("$each", Collections.singletonList(sourceKey.trim()))));
        }

        return Update.fromDocument(updateDoc);
    }

    // ==================================================================
    // 读
    // ==================================================================

    /** 图书总数 */
    public long count() {
        return mongoTemplate.count(new Query(), Book.class);
    }

    /** 按平台分类统计 */
    public long countByCategory(Integer categoryId) {
        if (categoryId == null) {
            return 0L;
        }
        return mongoTemplate.count(
                Query.query(Criteria.where("categories.categoryId").is(categoryId)), Book.class);
    }

    /** 按采集来源统计，sourceKey 形如 "category:300000" */
    public long countBySource(String sourceKey) {
        if (sourceKey == null || sourceKey.trim().isEmpty()) {
            return 0L;
        }
        return mongoTemplate.count(
                Query.query(Criteria.where("collectSource").is(sourceKey.trim())), Book.class);
    }

    /** 按 bookId 查一本 */
    public Book findByBookId(String bookId) {
        if (bookId == null || bookId.trim().isEmpty()) {
            return null;
        }
        return mongoTemplate.findOne(
                Query.query(Criteria.where("bookId").is(bookId.trim())), Book.class);
    }

    /** 按 bookId 批量查。分片查询，避免单次 $in 过大 */
    public List<Book> findByBookIds(Collection<String> bookIds) {
        List<Book> result = new ArrayList<Book>();
        if (bookIds == null || bookIds.isEmpty()) {
            return result;
        }
        List<String> all = new ArrayList<String>(bookIds);
        for (int i = 0; i < all.size(); i += IN_QUERY_CHUNK) {
            List<String> chunk = all.subList(i, Math.min(i + IN_QUERY_CHUNK, all.size()));
            Query query = Query.query(Criteria.where("bookId").in(chunk));
            result.addAll(mongoTemplate.find(query, Book.class));
        }
        return result;
    }

    /**
     * 分页查询。
     *
     * <p>先 count 再 find（两次往返）。这是第一期能接受的方案 —— 单用户本地跑，
     * 数据量几万级，count 很快。如果将来数据量上到百万，再换成
     * 「只给 hasNext、不给 total」的游标分页。
     *
     * <p>分页样板（count 短路 / skip+limit）统一走 {@link PageQueryUtil}；
     * 本类只负责「条件怎么拼」（{@link #buildCriteria}）和「按什么排」。
     */
    public PageResult<Book> pageQuery(BookQuery q) {
        BookQuery query = q == null ? new BookQuery() : q;

        // 主排序字段与方向都由 BookQuery 决定（字段走白名单校验，见 resolveSortPath()）
        Sort sort = Sort.by(query.isAsc() ? Sort.Direction.ASC : Sort.Direction.DESC,
                query.resolveSortPath());
        // 加个 _id 兜底排序，保证同分记录的分页顺序稳定（否则翻页可能重复/漏）
        sort = sort.and(Sort.by(Sort.Direction.ASC, "_id"));

        return PageQueryUtil.getPageResult(mongoTemplate, query.getSafePage(), query.getSafeSize(),
                buildCriteria(query), Book.class, sort);
    }

    /**
     * 把 BookQuery 翻译成 Mongo 的 {@code Criteria}。
     *
     * <p>多个条件用 {@code andOperator} 组合，而不是连续 {@code query.addCriteria(...)} ——
     * 后者在同一字段上叠加条件时会被覆盖，前者永远安全。
     */
    private Criteria buildCriteria(BookQuery q) {
        List<Criteria> conds = new ArrayList<Criteria>();

        if (q.getCategoryId() != null) {
            conds.add(Criteria.where("categories.categoryId").is(q.getCategoryId()));
        }
        if (q.getMinRating() != null) {
            // ★ 路径是顶层 newRating（推荐值 0~1000）。
            // 这里原来写成 newRatingDetail.newRating —— 那个字段不存在，
            // 导致评分筛选永远返回 0 条且不报错。详见 BookQuery#resolveSortPath() 的说明。
            conds.add(Criteria.where("newRating").gte(q.getMinRating()));
        }
        if (q.getMaxRating() != null) {
            conds.add(Criteria.where("newRating").lte(q.getMaxRating()));
        }
        if (q.getMinReadingCount() != null) {
            conds.add(Criteria.where("readingCount").gte(q.getMinReadingCount()));
        }
        String author = q.resolveAuthor();
        if (author != null) {
            // 精确匹配。不做正则 —— 见 BookQuery 类注释「刻意不支持的查询」
            conds.add(Criteria.where("author").is(author));
        }
        String minPublish = q.resolveMinPublishTime();
        if (minPublish != null) {
            // 字符串比较：publishTime 是定长零填充的 "yyyy-MM-dd HH:mm:ss"，字典序 == 时间序
            conds.add(Criteria.where("publishTime").gte(minPublish));
        }
        String maxPublish = q.resolveMaxPublishTime();
        if (maxPublish != null) {
            conds.add(Criteria.where("publishTime").lte(maxPublish));
        }
        String sourceKey = q.resolveSourceKey();
        if (sourceKey != null) {
            conds.add(Criteria.where("collectSource").is(sourceKey));
        }

        if (conds.isEmpty()) {
            return new Criteria();
        }
        if (conds.size() == 1) {
            return conds.get(0);
        }
        return new Criteria().andOperator(conds.toArray(new Criteria[0]));
    }

    // ==================================================================
    // 维护
    // ==================================================================

    /**
     * 按 {@code bookId} 局部更新（人工编辑，FR-B5）。
     *
     * <h3>为什么是「局部更新」而不是整本 {@code save()}</h3>
     * 界面上的编辑抽屉只让改十来个字段（书名/作者/简介/分类归属…）。
     * 如果走整本 {@code save()}，前端就得把 46 个字段一个不落地回传 ——
     * 少传一个，那个字段就被写成 null。这是「隐式清空」，是最难发现的一类数据损坏。
     * 局部更新只碰调用方明确给到的字段。
     *
     * <h3>⚠️ 刻意不动采集元数据</h3>
     * {@code lastCollectedAt} / {@code firstCollectedAt} / {@code collectSource}
     * 一律不改：它们记录的是「采集这件事发生过」，人工编辑不该伪造这个事实。
     * 否则「最近采集时间」会变成一个既不是采集时间也不是编辑时间的东西。
     *
     * @param sets 字段名 → 新值。空 Map 直接返回 false（不产生无意义的写）
     * @return 是否真的改到了文档
     */
    public boolean updateFields(String bookId, Map<String, Object> sets) {
        if (bookId == null || bookId.trim().isEmpty() || sets == null || sets.isEmpty()) {
            return false;
        }
        Update update = new Update();
        for (Map.Entry<String, Object> entry : sets.entrySet()) {
            update.set(entry.getKey(), entry.getValue());
        }
        return mongoTemplate.updateFirst(
                Query.query(Criteria.where("bookId").is(bookId.trim())), update, Book.class)
                .getMatchedCount() == 1L;
    }

    /**
     * 按 {@code bookId} 删除一本。
     *
     * <p><b>⚠️ 删了就不会再回来</b>：这本书下次采集时会被重新 upsert 进来
     * （如果它还在榜单/分类里）。所以「删除」的真实语义是
     * 「把这本不想要的书从库里清掉，直到下次采到它为止」，不是永久拉黑。
     * 要永久排除得靠「停用该目标」或改采集范围。
     *
     * @return 删除条数（0 或 1）
     */
    public long deleteByBookId(String bookId) {
        if (bookId == null || bookId.trim().isEmpty()) {
            return 0L;
        }
        return mongoTemplate.remove(
                Query.query(Criteria.where("bookId").is(bookId.trim())), Book.class)
                .getDeletedCount();
    }

    /**
     * 按 {@code bookId} 批量删除（FR-B6）。
     *
     * <p>分片执行，避免单次 {@code $in} 过大（同 {@link #findByBookIds}）。
     * 用 {@code $in} 一次删一批而不是循环单删：200 个勾选框就是 200 次往返，
     * 而 {@code $in} 只要 1 次。
     *
     * @return 实际删除条数
     */
    public long deleteByBookIds(Collection<String> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            return 0L;
        }
        List<String> all = new ArrayList<String>();
        for (String id : bookIds) {
            if (id != null && !id.trim().isEmpty()) {
                all.add(id.trim());
            }
        }
        if (all.isEmpty()) {
            return 0L;
        }

        long deleted = 0L;
        for (int i = 0; i < all.size(); i += IN_QUERY_CHUNK) {
            List<String> chunk = all.subList(i, Math.min(i + IN_QUERY_CHUNK, all.size()));
            Query query = Query.query(Criteria.where("bookId").in(chunk));
            deleted += mongoTemplate.remove(query, Book.class).getDeletedCount();
        }
        log.info("批量删除图书：请求 {} 条，实际删除 {} 条", all.size(), deleted);
        return deleted;
    }

    /**
     * 清空图书集合。
     *
     * <p>⚠️ <b>只给「重采全量」这个明确场景用</b>，且必须在接口层做二次确认。
     * 采集链路本身<b>不需要</b>清库 —— upsert 天然幂等。
     *
     * @return 删除条数
     */
    public long deleteAll() {
        long before = count();
        mongoTemplate.remove(new Query(), Book.class);
        log.warn("已清空 books 集合，删除 {} 条", before);
        return before;
    }
}
