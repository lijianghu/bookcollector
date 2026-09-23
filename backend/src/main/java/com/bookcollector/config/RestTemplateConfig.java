package com.bookcollector.config;

import com.bookcollector.collector.WereadHeaderInterceptor;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;

/**
 * RestTemplate 配置。
 *
 * <p><b>⚠️ 关键约束（R25）</b>：Spring Framework 5.2（Spring Boot 2.3）的
 * {@link HttpComponentsClientHttpRequestFactory} 只支持 <b>Apache HttpClient 4.5.x</b>，
 * 不支持 HttpClient 5.x。HttpClient 5 的支持是 Spring Framework 6.0 / Spring Boot 3.0 才加入的。
 *
 * <p>几处刻意设置：
 * <ul>
 *   <li>带 CookieStore —— 原 Python 用的是 {@code requests.Session()}，会保留会话 Cookie。
 *       这里用 {@link BasicCookieStore} 对齐这个行为。</li>
 *   <li>关掉 HttpClient 自带的自动重试 —— 重试策略由我们自己的 CollectLoop 控制，
 *       两套重试叠在一起会导致实际请求次数不可预期。</li>
 *   <li><b>换掉默认的错误处理器</b> —— 否则 4xx/5xx 会先被抛成异常，
 *       状态码丢失 + 「4xx 不重试」失效，见 {@link RawResponseErrorHandler}。</li>
 *   <li>连接池 —— 单机单任务其实用不满，但留着便于后续并发抓取。</li>
 * </ul>
 */
@Configuration
public class RestTemplateConfig {

    private static final Logger log = LoggerFactory.getLogger(RestTemplateConfig.class);

    private final WereadProperties props;

    public RestTemplateConfig(WereadProperties props) {
        this.props = props;
    }

    @Bean
    public CloseableHttpClient wereadHttpClient() {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(props.getConnectTimeout())
                .setConnectionRequestTimeout(props.getConnectTimeout())
                .setSocketTimeout(props.getReadTimeout())
                .build();

        PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();
        connManager.setMaxTotal(20);
        connManager.setDefaultMaxPerRoute(10);

        log.info("初始化微信读书 HttpClient：connectTimeout={}ms, readTimeout={}ms",
                props.getConnectTimeout(), props.getReadTimeout());

        return HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .setConnectionManager(connManager)
                .setDefaultCookieStore(new BasicCookieStore())
                // 自动重试交给我们自己的逻辑，避免两套重试叠加导致请求次数不可预期
                .disableAutomaticRetries()
                .build();
    }

    @Bean("wereadRestTemplate")
    public RestTemplate wereadRestTemplate(CloseableHttpClient wereadHttpClient) {
        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(wereadHttpClient);
        // 不要用 SimpleClientHttpRequestFactory —— 那个是 JDK HttpURLConnection，Header 行为不可控

        RestTemplate restTemplate = new RestTemplate(factory);
        // 用拦截器统一注入自定义 Header，避免被 RestTemplate 的默认 Accept 覆盖
        restTemplate.setInterceptors(Collections.singletonList(new WereadHeaderInterceptor(props)));
        // ★ 关键：不让 RestTemplate 把 4xx/5xx 变成异常，见 RawResponseErrorHandler
        restTemplate.setErrorHandler(new RawResponseErrorHandler());
        return restTemplate;
    }

    /**
     * 「不抛异常」的错误处理器 —— 让 HTTP 状态码原样交回给调用方。
     *
     * <h3>为什么必须换掉默认的 {@code DefaultResponseErrorHandler}</h3>
     * 默认实现在 4xx/5xx 上<b>抛异常</b>（{@code HttpClientErrorException} /
     * {@code HttpServerErrorException}）。这些异常会在
     * {@code WereadClient.fetchRaw} 的 {@code catch} 里被包成
     * {@code WereadRawResponse.error}，同时 {@code statusCode} 被写成 {@code -1}。
     * 后果有两个，而且都是静默的：
     *
     * <ol>
     *   <li><b>状态码丢了</b> —— 请求审计页把一次 500 显示成 {@code -1}，
     *       失败原因被描述成「网络异常 InternalServerError」，看起来像网线掉了，
     *       实际是服务端明确回了 500。</li>
     *   <li><b>「4xx 不重试」变成死代码</b> —— {@code WereadRawResponse.isRetryable()}
     *       的第一条判据是 {@code error != null}，而抛异常必然让 {@code error != null}，
     *       于是 404 也会被当成可重试，白白退避 1s + 3s 才失败。</li>
     * </ol>
     *
     * <p>由 {@code CollectRetryFailureIT} 钉住：5xx 恰好重试 3 次、4xx 恰好 1 次，
     * 且 {@code errorMsg} 分别是「HTTP 500」「HTTP 404」。
     *
     * <p>{@code hasError()} 恒返回 {@code false}，所以 {@code handleError()} 永远不会被调用；
     * 留着它只是为了让接口实现完整。
     */
    static class RawResponseErrorHandler implements ResponseErrorHandler {

        @Override
        public boolean hasError(ClientHttpResponse response) {
            // 「这一页算不算失败」由 CollectLoop 统一决策（它还要看 body 能不能解析出 books），
            // 这里不该替它下结论。
            return false;
        }

        @Override
        public void handleError(ClientHttpResponse response) {
            // 不会走到这里（hasError 恒为 false）
        }
    }
}
