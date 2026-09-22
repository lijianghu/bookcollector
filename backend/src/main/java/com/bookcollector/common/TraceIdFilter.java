package com.bookcollector.common;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * 给每个请求分配一个 traceId，放进 MDC 的 "threadId" key。
 *
 * <p>{@link ResultBean} 通过 {@code MDC.get("threadId")} 取值，所以 key 必须是 "threadId"。
 *
 * <p><b>注意（B4）</b>：{@code @Async} 线程不继承 MDC。采集任务线程需要自己
 * {@code MDC.put("threadId", ...)}，结束时 {@code MDC.remove("threadId")}，
 * 否则任务里的 traceId 会是 null，且线程池复用会导致 traceId 串味。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_KEY = "threadId";

    private static final String HEADER_TRACE_ID = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(HEADER_TRACE_ID);
        if (traceId == null || traceId.trim().isEmpty()) {
            traceId = newTraceId();
        }
        MDC.put(TRACE_ID_KEY, traceId);
        response.setHeader(HEADER_TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 必须清理，否则 Tomcat 线程复用时会串味
            MDC.remove(TRACE_ID_KEY);
        }
    }

    private String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
