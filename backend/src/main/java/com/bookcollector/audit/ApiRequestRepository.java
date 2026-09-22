package com.bookcollector.audit;

import com.bookcollector.audit.entity.ApiRequest;
import com.bookcollector.collector.WereadPage;
import com.bookcollector.collector.WereadRawResponse;
import com.bookcollector.common.enums.TargetType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * 请求审计落库 —— 原 MySQL 的 {@code api_requests} 表。
 *
 * <p>每打完一次接口就落一条，用途：
 * <ul>
 *   <li>排查「某页为什么没数据」—— 能看到 statusCode / resultCount / errorMsg</li>
 *   <li>观测接口健康度 —— {@code responseTimeMs} 的分布</li>
 * </ul>
 *
 * <p><b>只插不改</b>。写入量大（21 分类 × 数百页），改一条审计记录没有意义。
 *
 * <p>审计失败<b>不抛异常</b>：它不该影响采集主流程。
 */
@Component
public class ApiRequestRepository {

    private static final Logger log = LoggerFactory.getLogger(ApiRequestRepository.class);

    private final MongoTemplate mongoTemplate;

    public ApiRequestRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * 记录一次分页请求。
     *
     * @param runId     归属的运行 ID，可为 null（手工探针触发时）
     * @param type      目标类型
     * @param targetId  目标 ID
     * @param pageIndex 第几页（从 1 开始，给人看的）
     * @param maxIndex  本次用的游标值（给机器看的）
     * @param resp      原始响应
     * @param page      解析后的信封；解析失败时传 null
     * @param resultCount 本页返回的记录条数
     */
    public void recordPage(String runId, TargetType type, String targetId,
                           int pageIndex, int maxIndex,
                           WereadRawResponse resp, WereadPage page, int resultCount) {
        try {
            ApiRequest record = ApiRequest.builder()
                    .runId(runId)
                    .targetType(type == null ? null : type.name())
                    .targetId(targetId)
                    .pageIndex(pageIndex)
                    .maxIndex(maxIndex)
                    .url(resp.getUrl())
                    .statusCode(resp.getStatusCode())
                    .responseTimeMs((int) resp.getCostMs())
                    .resultCount(resultCount)
                    // 措辞和「任务失败原因」共用同一个实现，界面上两处说法才不会打架。
                    // 注意这里**不是**只看 resp.getError()：HTTP 4xx/5xx 时 error 是 null，
                    // 只有 statusCode 有值，光看 error 会让审计页的「原因」一栏空着。
                    .errorMsg(truncate(resp.describeFailure(), 500))
                    .build();

            if (page != null) {
                record.setSynckey(page.getSynckey());
                record.setTotalCount(page.getTotalCount());
                record.setHasMore(page.isHasMore());
            }

            mongoTemplate.insert(record);
        } catch (Exception e) {
            log.warn("写请求审计失败（{}/{} 第 {} 页）：{}", type, targetId, pageIndex, e.getMessage());
        }
    }

    /** 错误信息可能很长（含整个 HTML 响应），截断后再存 */
    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
