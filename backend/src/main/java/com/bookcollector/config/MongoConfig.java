package com.bookcollector.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * MongoDB 配置。
 *
 * <p>{@code MongoTemplate} 本身由 Spring Boot 的 {@code MongoDataAutoConfiguration} 提供，
 * 这里只做两件事：开启审计、把 {@code DateTimeProvider} 钉死。
 *
 * <h3>⚠️ 审计的生效范围（很容易踩的坑）</h3>
 * {@code @CreatedDate} / {@code @LastModifiedDate} <b>只在「按实体保存」时生效</b>，
 * 也就是 {@code MongoTemplate.save(entity)} / {@code insert(entity)} /
 * {@code Repository.save(entity)} 这些走 {@code BeforeSaveEvent} 的路径。
 *
 * <p>而 <b>{@code bulkOps().upsert(query, Update)} 不走审计</b> —— 它操作的是
 * {@code Update} 对象而不是实体，没有实体就没有字段可填。所以：
 * <ul>
 *   <li>{@code ApiRequest} / {@code CollectTask} / {@code TaskLog} / {@code TaxonomyItem} /
 *       {@code Setting} / {@code CollectCursor} —— 用 {@code save()}，审计生效，字段自动填</li>
 *   <li>{@code Book} —— 用批量 upsert（性能考虑），
 *       {@code firstCollectedAt} / {@code lastCollectedAt} 由
 *       {@code BookRepository#upsertMany} <b>手工写入</b>，不能依赖审计</li>
 * </ul>
 *
 * <p>所以 {@code Book} 上的那两个时间字段刻意<b>没有</b>加 {@code @CreatedDate} 注解 ——
 * 加了会产生「以为自动填了、实际是 null」的错觉，比不加更危险。
 *
 * @see MongoIndexInitializer
 */
@Configuration
@EnableMongoAuditing
public class MongoConfig {

    /**
     * 审计用的时间源。
     *
     * <p>默认的 {@code CurrentDateTimeProvider} 返回 {@code LocalDateTime.now()}，
     * 会跟 JVM 默认时区走。这里显式指定 {@link ZoneId#systemDefault()}，
     * 是为了把行为钉死：读代码的人一眼能看出「存的是本地时间」。
     *
     * <p>Spring Data Mongo 内置的 {@code Jsr310Converters} 会把这个
     * {@code LocalDateTime} 转成目标字段的类型（{@code Date}）。
     */
    @Bean
    public DateTimeProvider mongoDateTimeProvider() {
        return () -> Optional.of(LocalDateTime.now(ZoneId.systemDefault()));
    }
}
