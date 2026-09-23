package com.bookcollector.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 采集任务专用线程池。
 *
 * <h3>为什么必须单独一个池，不能用 Spring 默认的</h3>
 * Spring Boot 默认的 {@code applicationTaskExecutor} 是给「短任务」用的，
 * 而采集任务是<b>分钟级甚至小时级</b>的长任务。混用会把默认池占满，
 * 连带把别的 {@code @Async} 业务一起饿死。
 *
 * <h3>为什么 core=1 / max=2，而不是 core=2</h3>
 * 这是一个容易踩的坑：{@link ThreadPoolExecutor} 的扩容规则是
 * 「先塞队列，队列满了才开新线程」。所以 <b>core=1 + 有界队列</b> 的实际行为是
 * 「最多 1 个线程在跑，其余排队」，max=2 几乎永远用不到。
 *
 * <p>这是<b>故意的</b>：
 * <ul>
 *   <li>真正的并发闸门在 {@link com.bookcollector.task.TaskRegistry} +
 *       {@code bookcollector.task.max-concurrent-runs}（默认 1），
 *       不在线程池。线程池只是执行载体。</li>
 *   <li>max=2 是留给「将来放开并发」的余量，不需要改这里。</li>
 * </ul>
 *
 * <h3>拒绝策略用 AbortPolicy</h3>
 * 队列满时直接抛 {@code TaskRejectedException}，由 {@code TaskService} 捕获后
 * 把任务标成 FAILED 并释放采集权。用 {@code CallerRunsPolicy} 会让 HTTP 线程
 * 去跑采集 —— 一个请求卡住几十分钟，这是绝对不能接受的。
 *
 * <h3>关停行为</h3>
 * {@code waitForTasksToCompleteOnShutdown=true} + 20 秒上限。
 * 配合 {@code TaskRegistry} 监听 {@code ContextClosedEvent} 先取消所有 handle，
 * 暂停中的线程会被唤醒并正常退出，所以实际几乎不会等满 20 秒。
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /** 采集线程池的 bean 名，{@code @Async} 上要写这个 */
    public static final String COLLECT_EXECUTOR = "collectExecutor";

    private final TaskProperties taskProperties;

    public AsyncConfig(TaskProperties taskProperties) {
        this.taskProperties = taskProperties;
    }

    @Bean(COLLECT_EXECUTOR)
    public ThreadPoolTaskExecutor collectExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(taskProperties.getQueueCapacity());
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("collect-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 关停时先等正在跑的任务收尾，别把一次 upsert 掐在半路
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        log.info("采集线程池已就绪：core=1, max=2, queue={}, 并发闸门={}",
                taskProperties.getQueueCapacity(), taskProperties.getMaxConcurrentRuns());
        return executor;
    }
}
