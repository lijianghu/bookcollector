package com.bookcollector.task.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.util.Date;

/**
 * 运行日志。原 Python 只往控制台/文件打日志，本次改造后落库，前端可以按 run 查看。
 *
 * <p><b>只在关键节点写</b>（每页一条 + 异常一条 + 状态流转一条），
 * 不写 debug 级流水账 —— 否则一个跑满的分类会灌进几万条文档。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "collect_task_logs")
public class TaskLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 日志级别：INFO / WARN / ERROR */
    public static final String LEVEL_INFO = "INFO";
    public static final String LEVEL_WARN = "WARN";
    public static final String LEVEL_ERROR = "ERROR";

    @Id
    private String id;

    /** 所属运行 ID。为空表示是任务级日志（如「任务已创建」） */
    private String runId;

    /** 所属任务 ID */
    private String taskId;

    /** 日志级别 */
    private String level;

    /** 日志正文 */
    private String message;

    /** 落库时间。由审计自动填充 */
    @CreatedDate
    private Date createdAt;
}
