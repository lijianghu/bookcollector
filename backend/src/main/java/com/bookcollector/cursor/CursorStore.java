package com.bookcollector.cursor;

import com.bookcollector.common.enums.TargetType;
import com.bookcollector.cursor.entity.CollectCursor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 采集游标的读写。
 *
 * <h3>游标到底是什么</h3>
 * 微信读书这个接口用 {@code maxIndex} 分页，而 {@code maxIndex} 的取值是
 * <b>本页最后一条记录的 {@code searchIdx}</b>。所以游标不是一个页码，
 * 是一个整数序号（文学分类实测第一页最后一条的 {@code searchIdx} = 20）。
 *
 * <h3>🔴 一条铁律：先落库，再推进游标</h3>
 * {@link #advance} 必须<b>在图书写入成功之后</b>调用。顺序反了的话，
 * 一旦在两者之间崩溃，游标已经跳过这批书，它们就<b>永久丢失</b>了 ——
 * 下次续传会从更后面开始，谁也不知道中间少了什么。
 *
 * <p>反过来（先落库后推进）最坏情况只是「重复采一次同一页」，
 * 而 upsert 是幂等的，重复采没有副作用。这就是为什么顺序不能反。
 */
@Component
public class CursorStore {

    private static final Logger log = LoggerFactory.getLogger(CursorStore.class);

    private final MongoTemplate mongoTemplate;

    public CursorStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * 读游标。
     *
     * @return 游标记录；从未采过时返回 {@code null}
     */
    public CollectCursor load(TargetType type, String targetId) {
        Query query = Query.query(Criteria.where("targetType").is(type.name())
                .and("targetId").is(targetId));
        return mongoTemplate.findOne(query, CollectCursor.class);
    }

    /**
     * 读游标的数值，供「下一页请求用哪个 maxIndex」。
     *
     * @return 游标值；从未采过、或已采到底时为 0（从头开始）
     */
    public int loadCursor(TargetType type, String targetId) {
        CollectCursor cursor = load(type, targetId);
        if (cursor == null || cursor.getMaxIndex() == null) {
            return 0;
        }
        return cursor.getMaxIndex();
    }

    /**
     * 推进游标（幂等 upsert）。
     *
     * <p><b>⚠️ 必须在图书落库成功之后调用</b>，见类注释。
     *
     * <p>这里用 {@code Update} 而不是实体 {@code save()}，所以
     * {@code CollectCursor.updatedAt} 上的 {@code @LastModifiedDate} <b>不会生效</b>，
     * 时间戳在这里手工写。
     *
     * @param maxIndex       新的游标值
     * @param totalCollected 该目标<b>累计处理过</b>的条数（调用方负责把本次的量加上历史基数，
     *                       见 {@link com.bookcollector.collector.CollectLoop#run}）
     * @param totalCount     接口报告的总书数，可为 null
     * @param finished       是否已采到底（hasMore=false）
     * @param runId          本次运行的 ID，可为 null
     */
    public void advance(TargetType type, String targetId, String targetName,
                        int maxIndex, long totalCollected, Integer totalCount,
                        boolean finished, String runId) {
        Query query = Query.query(Criteria.where("targetType").is(type.name())
                .and("targetId").is(targetId));

        Update update = new Update()
                .set("maxIndex", maxIndex)
                .set("totalCollected", totalCollected)
                .set("updatedAt", new Date())
                .setOnInsert("targetType", type.name())
                .setOnInsert("targetId", targetId);
        if (targetName != null) {
            update.set("targetName", targetName);
        }
        if (totalCount != null) {
            update.set("totalCount", totalCount);
        }
        if (runId != null) {
            update.set("lastRunId", runId);
        }
        // finished 只在「采到底」时置 true，且不允许被后续的 false 覆盖回去 ——
        // 否则一次「只采 5 页」的任务会把「已采到底」的状态抹掉。
        if (finished) {
            update.set("finished", Boolean.TRUE);
        } else {
            update.setOnInsert("finished", Boolean.FALSE);
        }

        mongoTemplate.upsert(query, update, CollectCursor.class);
    }

    /**
     * 重置游标到 0。
     *
     * <p>⚠️ 只影响游标，<b>不删已采到的书</b>。重采全量应该：
     * 先 {@code reset} 再跑 —— 因为 upsert 幂等，重复采到的书会被更新而不是重复插入。
     *
     * <p>{@code totalCollected} 一并归零：语义上「从头重采」等于「重新计数」。
     * 如果这里不归零，第二次全量采集会把计数翻倍，那个数字就没意义了。
     */
    public void reset(TargetType type, String targetId) {
        Query query = Query.query(Criteria.where("targetType").is(type.name())
                .and("targetId").is(targetId));
        Update update = new Update()
                .set("maxIndex", 0)
                .set("finished", Boolean.FALSE)
                .set("totalCollected", 0L)
                .set("updatedAt", new Date());
        mongoTemplate.upsert(query, update, CollectCursor.class);
        log.info("已重置游标：{} / {}", type, targetId);
    }

    /**
     * 手工指定游标起点（FR-E3）。
     *
     * <p>{@code maxIndex <= 0} 时等价于 {@link #reset}（重置为 0），
     * 这样「重置」和「指定起点」在前端可以是同一个表单，填 0 就是重置。
     *
     * <h3>为什么 totalCollected 也归零</h3>
     * 与 {@code reset} 保持口径一致：两个动作的语义都是「这个目标要重新开始采」。
     * 如果这里保留累计值、那里归零，同一个数字会有两种含义，
     * 而它本来就不是个可信的指标（见 {@link CollectCursor#totalCollected} 的说明）。
     * 想知道「这个目标到底贡献了多少本书」，应该按
     * {@code books.collectSource} 去 count —— 那个是去重的。
     *
     * <p>{@code finished} 置回 false：手工把游标往回拨之后，后面显然还有数据。
     */
    public void setCursor(TargetType type, String targetId, String targetName, int maxIndex) {
        if (maxIndex <= 0) {
            reset(type, targetId);
            return;
        }
        Query query = Query.query(Criteria.where("targetType").is(type.name())
                .and("targetId").is(targetId));

        Update update = new Update()
                .set("maxIndex", maxIndex)
                .set("finished", Boolean.FALSE)
                .set("totalCollected", 0L)
                .set("updatedAt", new Date())
                .setOnInsert("targetType", type.name())
                .setOnInsert("targetId", targetId);
        if (targetName != null && !targetName.trim().isEmpty()) {
            update.set("targetName", targetName.trim());
        }
        mongoTemplate.upsert(query, update, CollectCursor.class);
        log.info("已指定游标起点：{} / {} -> maxIndex={}", type, targetId, maxIndex);
    }

    /** 按 Mongo _id 查一条游标（供 DELETE 接口用） */
    public CollectCursor findById(String id) {
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        return mongoTemplate.findById(id.trim(), CollectCursor.class);
    }

    /** 全部游标，按最近更新倒序。用于「断点续传」页 */
    public List<CollectCursor> findAll() {
        Query query = new Query();
        query.with(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "updatedAt"));
        return mongoTemplate.find(query, CollectCursor.class);
    }

    /** 删除某个目标的游标 */
    public void delete(TargetType type, String targetId) {
        Query query = Query.query(Criteria.where("targetType").is(type.name())
                .and("targetId").is(targetId));
        mongoTemplate.remove(query, CollectCursor.class);
    }
}
