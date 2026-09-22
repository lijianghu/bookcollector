package com.bookcollector.cursor.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.util.Date;

/**
 * 采集游标 —— 断点续传的唯一依据。
 *
 * <p><b>游标是什么</b>：微信读书这个接口用 {@code maxIndex} 分页，
 * 而 {@code maxIndex} 的取值是「本页最后一条记录的 {@code searchIdx}」。
 * 所以游标不是页码，是一个整数序号。把它持久化下来，任务中断后就能接着跑。
 *
 * <p><b>为什么单独一个集合，而不是复用 collect_task_runs.currentCursor</b>：
 * 游标是「目标」的属性（文学这个分类翻到第几了），不是「某一次运行」的属性。
 * 换一个任务采同一个分类，应该共享同一个游标。
 *
 * <p><b>一个 target 一行</b>，靠 {@code (targetType, targetId)} 唯一索引保证。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "collect_cursors")
public class CollectCursor implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    /** 采集目标类型：CATEGORY / RANKING */
    private String targetType;

    /** 采集目标 ID */
    private String targetId;

    /** 目标名称快照 */
    private String targetName;

    /**
     * 当前游标。下一页请求用 {@code maxIndex=this}。
     * <b>写入顺序铁律：先落库图书，再推进游标。</b>反过来的话，
     * 一旦在中间崩溃，这批书就永久丢了（游标已经跳过它们）。
     */
    private Integer maxIndex;

    /**
     * 该目标<b>累计处理过</b>的图书条数（跨运行累加）。
     *
     * <p>口径说明，避免误读：
     * <ul>
     *   <li>它是「处理条数」，不是「去重后的图书数」—— 同一页被重采两次会算两次。
     *       实际续传场景下游标只前进不后退，所以这个偏差只在人为重采时出现。</li>
     *   <li>想拿「这个目标到底贡献了多少本书」，应该按 {@code books.collectSource}
     *       去 count，那个是去重的。</li>
     *   <li>{@code resetCursor=true} 会把它归零（重新全量采集 = 重新计数）。</li>
     * </ul>
     */
    private Long totalCollected;

    /** 接口报告的总书数，用于算进度 */
    private Integer totalCount;

    /** 是否已经采到底（hasMore=false） */
    private Boolean finished;

    /** 最近一次推进游标的运行 ID */
    private String lastRunId;

    /** 最近一次推进时间。由审计自动填充 */
    @LastModifiedDate
    private Date updatedAt;
}
