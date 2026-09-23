package com.bookcollector.collector;

import com.bookcollector.config.WereadProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * 给每个请求注入自定义 Header。
 *
 * <p>为什么用拦截器而不是在每次调用时手动 set header：
 * RestTemplate 的 message converter 会根据 body 类型自行决定 {@code Accept}，
 * 手动 set 的值可能被覆盖。拦截器在最后一步执行，能确保 Header 是我们想要的值。
 *
 * <p><b>⚠️ 关于 Accept-Encoding</b>：原 Python 写的是 {@code gzip, deflate, br}，
 * 但 Apache HttpClient 4.5 <b>不支持 Brotli</b>。如果照抄带上 {@code br}，
 * 服务端可能返回 Brotli 编码，而客户端解不开 → 乱码。
 * 所以这里刻意只写 {@code gzip, deflate}。
 */
public class WereadHeaderInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WereadHeaderInterceptor.class);

    /** 与 Python 版逐字一致 */
    public static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** ⚠️ 去掉了 br，见类注释 */
    public static final String ACCEPT_ENCODING = "gzip, deflate";

    private final WereadProperties props;

    public WereadHeaderInterceptor(WereadProperties props) {
        this.props = props;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request,
                                        byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        HttpHeaders headers = request.getHeaders();

        headers.set("User-Agent", USER_AGENT);
        headers.set("Accept", "application/json, text/plain, */*");
        headers.set("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.set("Accept-Encoding", ACCEPT_ENCODING);
        headers.set("Connection", "keep-alive");
        headers.set("Referer", props.getReferer());

        if (log.isDebugEnabled()) {
            log.debug("→ {} {} | UA={}... | Referer={}",
                    request.getMethod(), request.getURI(),
                    USER_AGENT.substring(0, 30), props.getReferer());
        }

        return execution.execute(request, body);
    }
}
