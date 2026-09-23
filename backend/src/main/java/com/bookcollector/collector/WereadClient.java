package com.bookcollector.collector;

import com.bookcollector.collector.dto.WereadRawResponse;
import com.bookcollector.common.enums.TargetType;
import com.bookcollector.config.WereadProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

/**
 * 微信读书列表接口客户端。
 *
 * <p>只负责「发请求 + 拿到原始 JSON 字符串」，不做解析（解析是 {@code BookParser} 的事）。
 *
 * <p><b>⚠️ 一个容易踩的坑</b>：这里刻意用 {@code byte[].class} 而不是 {@code String.class}。
 * RestTemplate 用 {@code StringHttpMessageConverter} 处理 String 响应时，如果服务端
 * 返回的 {@code Content-Type} 里没带 charset，会退回 <b>ISO-8859-1</b> 解码 → 中文全乱码。
 * 拿 byte[] 自己按 UTF-8 构造 String，可以彻底绕开这个问题。
 */
@Component
public class WereadClient {

    private static final Logger log = LoggerFactory.getLogger(WereadClient.class);

    private final RestTemplate restTemplate;
    private final WereadProperties props;

    public WereadClient(@Qualifier("wereadRestTemplate") RestTemplate restTemplate,
                        WereadProperties props) {
        this.restTemplate = restTemplate;
        this.props = props;
    }

    /**
     * 拼请求 URL。与原 Python 逐字对齐。
     */
    public String buildUrl(TargetType type, String targetId, int maxIndex) {
        StringBuilder sb = new StringBuilder(props.getBaseUrl());
        sb.append('/').append(targetId).append("?maxIndex=").append(maxIndex);
        if (type == TargetType.RANKING) {
            sb.append("&rank=1");
        }
        return sb.toString();
    }

    /**
     * 拉一页原始 JSON。
     *
     * @param type     目标类型
     * @param targetId 分类 ID 或榜单类型
     * @param maxIndex 分页游标（0 表示第一页）
     * @return 原始响应（含 URL、状态码、耗时、body），不抛业务异常，失败由调用方决定怎么处理
     */
    public WereadRawResponse fetchRaw(TargetType type, String targetId, int maxIndex) {
        String url = buildUrl(type, targetId, maxIndex);
        long start = System.currentTimeMillis();
        try {
            ResponseEntity<byte[]> resp = restTemplate.getForEntity(url, byte[].class);
            long cost = System.currentTimeMillis() - start;
            byte[] bodyBytes = resp.getBody();
            // 手工按 UTF-8 解码，见类注释
            String body = bodyBytes == null ? "" : new String(bodyBytes, StandardCharsets.UTF_8);

            if (log.isDebugEnabled()) {
                log.debug("← {} {} | {}ms | {} bytes", resp.getStatusCodeValue(), url, cost,
                        bodyBytes == null ? 0 : bodyBytes.length);
            }

            return WereadRawResponse.builder()
                    .url(url)
                    .statusCode(resp.getStatusCodeValue())
                    .costMs(cost)
                    .body(body)
                    .build();

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.warn("请求失败 {} {} | {}ms | {}", type, targetId, cost, e.getMessage());
            return WereadRawResponse.builder()
                    .url(url)
                    .statusCode(-1)
                    .costMs(cost)
                    .body(null)
                    .error(e)
                    .build();
        }
    }
}
