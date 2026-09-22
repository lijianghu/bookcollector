package com.bookcollector.auth;

import com.bookcollector.common.ResultBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;

/**
 * Token 校验拦截器（FR-A3）。
 *
 * <h3>⚠️ 实现偏差：需求写的是「Filter」，这里用的是 Spring MVC 的 Interceptor</h3>
 * 需求 FR-A3 原文是「后端用一个 {@code Filter} 校验 token」。实际用
 * {@link HandlerInterceptor} 实现，原因有两条：
 * <ol>
 *   <li><b>能按路径精确放行</b>。Filter 只能靠 {@code urlPatterns} 做前缀匹配，
 *       而我们要放行的东西（{@code /api/auth/login}、{@code /api/ping}）
 *       和要拦截的东西（其余 {@code /api/**}）混在同一前缀下。
 *       Interceptor 的 {@code excludePathPatterns} 表达力正好够用。</li>
 *   <li><b>不与 Swagger 打架</b>。{@code /v3/api-docs} 与 {@code /swagger-ui/**}
 *       不在 {@code /api/**} 下，天然不被拦；如果做成 Filter 就得手工维护白名单，
 *       加一个新静态资源路径就要改一次拦截逻辑。</li>
 * </ol>
 * 行为契约（token 怎么传、失败返回什么）与需求完全一致，只是拦截点从 Servlet 层
 * 上移到了 DispatcherServlet 层。{@code TraceIdFilter} 仍然是 Filter —— 它需要
 * 覆盖所有请求（包括静态资源），那里 Filter 才是对的工具。
 *
 * <h3>🔴 失败时 HTTP 状态码仍然是 200</h3>
 * 这是既有约定：前端 axios 拦截器只看 body 里的 {@code code}，不看 HTTP 状态码
 * （见 {@code frontend/src/api/request.ts}）。所以这里也必须写 200 + {@code code=4100}，
 * 否则前端会走到「HTTP 层错误」分支，弹出一句「请求异常：HTTP 401」而不是
 * 「身份已失效，请重新登录」。
 */
@Component
public class TokenInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TokenInterceptor.class);

    /** 标准头。前端 axios 发的是 {@code Authorization: Bearer <token>} */
    public static final String HEADER_AUTHORIZATION = "Authorization";

    /** 备用头，方便 curl 手工调试：{@code -H "X-Token: xxx"} */
    public static final String HEADER_X_TOKEN = "X-Token";

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthProperties properties;
    private final ObjectMapper objectMapper;

    public TokenInterceptor(AuthProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        // CORS 预检请求不带自定义头，放行（浏览器自己会接着发真正的请求）
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String token = resolveToken(request);
        if (isValid(token)) {
            return true;
        }
        log.warn("Token 校验失败：{} {}（token={}）",
                request.getMethod(), request.getRequestURI(),
                token == null ? "缺失" : "不匹配");
        writeSessionInvalid(response);
        return false;
    }

    /**
     * 取 token。三种来源依次尝试，覆盖浏览器与手工调试两种场景：
     * <ol>
     *   <li>{@code Authorization: Bearer <token>} —— 前端正常路径</li>
     *   <li>{@code X-Token: <token>} —— curl 调试用</li>
     *   <li>{@code ?token=<token>} —— 直接浏览器地址栏打开某个 GET 接口时用</li>
     * </ol>
     */
    private String resolveToken(HttpServletRequest request) {
        String auth = request.getHeader(HEADER_AUTHORIZATION);
        if (auth != null && !auth.trim().isEmpty()) {
            String v = auth.trim();
            if (v.length() > BEARER_PREFIX.length()
                    && v.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
                return v.substring(BEARER_PREFIX.length()).trim();
            }
            return v;
        }
        String xToken = request.getHeader(HEADER_X_TOKEN);
        if (xToken != null && !xToken.trim().isEmpty()) {
            return xToken.trim();
        }
        String param = request.getParameter("token");
        if (param != null && !param.trim().isEmpty()) {
            return param.trim();
        }
        return null;
    }

    /** 定长比较，避免 {@code equals} 的短路时序差异（本地服务其实无所谓，但没成本） */
    private boolean isValid(String token) {
        String expected = properties.getToken();
        if (token == null || expected == null || token.length() != expected.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= token.charAt(i) ^ expected.charAt(i);
        }
        return diff == 0;
    }

    /**
     * 写回 {@code code=4100} 的统一返回体。
     *
     * <p>不走 {@code GlobalExceptionHandler}：拦截器返回 {@code false} 之后
     * 异常处理器根本不会介入，响应体必须自己写。
     */
    private void writeSessionInvalid(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");

        ResultBean<Void> body = ResultBean.err(ResultBean.code_session_invalid, "身份已失效，请重新登录！");
        String json = objectMapper.writeValueAsString(body);

        PrintWriter writer = response.getWriter();
        writer.write(json);
        writer.flush();
    }
}
