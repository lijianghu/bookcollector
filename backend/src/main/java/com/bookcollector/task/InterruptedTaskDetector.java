package com.bookcollector.task;

import com.bookcollector.common.enums.TaskStatus;
import com.bookcollector.task.entity.CollectTask;
import com.bookcollector.task.entity.TaskRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * 启动时的「中断清理」：把上次进程留下的 RUNNING / PAUSED 状态标成 {@code INTERRUPTED}。
 *
 * <h3>为什么必须有这一步</h3>
 * 任务状态存在 MongoDB 里，进程被杀（kill -9 / 关窗口 / 崩溃）时来不及写终态，
 * 库里就会留下「RUNNING」的记录。但内存注册表 {@link TaskRegistry} 是空的 ——
 * 没有任何线程在跑它。于是 UI 上会永远显示「运行中」，进度也永远不动，
 * 而用户完全没有办法让它结束（因为没人在跑，取消也没对象）。
 *
 * <p>这就是「假死」。启动时统一清理一次，是唯一可靠的解法 ——
 * 因为<b>只有进程启动的那一刻，能确定「所有 RUNNING 都是假的」</b>。
 *
 * <h3>为什么标 INTERRUPTED 而不是 FAILED</h3>
 * 语义不同：FAILED 是「试过了，不行」；INTERRUPTED 是「被意外打断了，但<b>游标还在</b>」。
 * 前者需要重试，后者只需要恢复。{@link TaskStatus#INTERRUPTED} 不是终态，
 * {@code TaskService.resume} 可以直接把它拉起来接着采。
 *
 * <h3>为什么这个类不能省</h3>
 * 它是「游标保留」这个承诺的另一半：{@code collect_cursors} 保证数据不丢，
 * 这个类保证用户知道「该去点恢复了」。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class InterruptedTaskDetector implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InterruptedTaskDetector.class);

    /** 写进 run.errorMsg 的原因，前端可直接展示 */
    public static final String REASON = "JVM 重启导致中断（游标已保留，可恢复）";

    private final MongoTemplate mongoTemplate;

    public InterruptedTaskDetector(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        int runs = markRuns();
        int tasks = markTasks();
        if (runs == 0 && tasks == 0) {
            log.info("中断检测：没有遗留的活跃任务，无需清理");
            return;
        }
        log.warn("中断检测：把 {} 条运行记录、{} 个任务标为 INTERRUPTED（游标保留，可恢复）",
                runs, tasks);
    }

    private int markRuns() {
        Query q = Query.query(Criteria.where("status")
                .in(Arrays.asList(TaskStatus.RUNNING.name(), TaskStatus.PAUSED.name())));
        Update u = new Update()
                .set("status", TaskStatus.INTERRUPTED.name())
                .set("finishedAt", new Date())
                .set("errorMsg", REASON)
                .set("lastProgressAt", new Date());
        return (int) mongoTemplate.updateMulti(q, u, TaskRun.class).getModifiedCount();
    }

    private int markTasks() {
        Query q = Query.query(Criteria.where("status")
                .in(Arrays.asList(TaskStatus.RUNNING.name(), TaskStatus.PAUSED.name())));
        Update u = new Update()
                .set("status", TaskStatus.INTERRUPTED.name())
                .set("updatedAt", new Date());
        return (int) mongoTemplate.updateMulti(q, u, CollectTask.class).getModifiedCount();
    }

    /** 仅供测试与运维排查：列出当前库里「看起来还活着」的记录 */
    public List<TaskRun> peekActiveRuns() {
        Query q = Query.query(Criteria.where("status")
                .in(Arrays.asList(TaskStatus.RUNNING.name(), TaskStatus.PAUSED.name())));
        return mongoTemplate.find(q, TaskRun.class);
    }
}
