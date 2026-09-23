package com.bookcollector.auth.interceptor;

import cn.dev33.satoken.exception.NotLoginException;
import com.bookcollector.auth.stp.AuthStpUtil;
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
 * 鉴权拦截器（FR-A3）—— 校验 {@code /api/**} 的登录态，失败写 {@code code=4100}。
 *
 * <p>这是 {@code TokenInterceptor}（写死 token 版本）的替代实现。
 * 差别只有一处：<b>「这个 token 是否有效」的判断从「和配置里的字符串比」
 * 变成了「问 Sa-Token 要登录态」</b> —— 于是登出、过期、被踢下线这些
 * 以前不存在的概念现在都自动成立。
 *
 * <h3>为什么拦截点仍然是 {@code HandlerInterceptor} 而不是 {@code Filter}</h3>
 * <ol>
 *   <li><b>能按路径精确放行</b>。Filter 只能靠 {@code urlPatterns} 做前缀匹配，
 *       而我们要放行的东西（{@code /api/auth/login}、{@code /api/ping}）
 *       和要拦截的东西（其余 {@code /api/**}）混在同一前缀下。
 *       Interceptor 的 {@code excludePathPatterns} 表达力正好够用。</li>
 *   <li><b>不与 Swagger 打架</b>。{@code /v3/api-docs} 与 {@code /swagger-ui/**}
 *       不在 {@code /api/**} 下，天然不被拦；如果做成 Filter 就得手工维护白名单，
 *       加一个新静态资源路径就要改一次拦截逻辑。</li>
 * </ol>
 * （{@code TraceIdFilter} 仍然是 Filter —— 它需要覆盖所有请求，那里 Filter 才是对的工具。
 *   Sa-Token 自己注册的 {@code SaTokenContextFilterForServlet} 同理，它负责给每个请求
 *   铺好 Sa-Token 的上下文，是「基础设施」不是「业务拦截」。）
 *
 * <h3>🔴 失败时 HTTP 状态码仍然是 200</h3>
 * 这是既有约定：前端 axios 拦截器只看 body 里的 {@code code}，不看 HTTP 状态码
 * （见 {@code frontend/src/api/request.ts}）。所以这里也必须写 200 + {@code code=4100}，
 * 否则前端会走到「HTTP 层错误」分支，弹出一句「请求异常：HTTP 401」而不是
 * 「身份已失效，请重新登录」。
 *
 * <h3>为什么这里捕获 {@code NotLoginException} 而不用全局异常处理器</h3>
 * {@code preHandle} 里抛出的异常虽然也能被 {@code @ControllerAdvice} 接住，
 * 但那是一条不那么常用的路径。这里的失败是<b>每一次未带 token 的请求</b>都会走的
 * 热路径，一旦异常解析链因为任何原因没兜住，表现是「所有未登录请求 500」——
 * 影响面大、且症状与根因（异常解析器）看起来毫无关系。
 * 所以沿用项目既有做法：<b>自己写响应体</b>，不依赖异常处理链。
 * （{@code ResultBean.err(...)} 内部会带出 MDC 里的 traceId，所以自写响应
 *   并不比走异常处理器少任何信息。）
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    private final ObjectMapper objectMapper;

    public AuthInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        // CORS 预检请求不带自定义头，放行（浏览器自己会接着发真正的请求）
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        try {
            // Sa-Token 会依次从 header / 参数 / cookie 里找 token（具体见 application.yml 的
            // sa-token.* 配置），并校验会话是否还在
            AuthStpUtil.checkLogin();
            return true;
        } catch (NotLoginException e) {
            // e.getMessage() 形如「未登录：xxx」/「token 无效：xxx」/「token 已过期」
            // 这些细节只进日志，不返回给前端 —— 对使用者来说「重新登录」是唯一动作
            log.warn("鉴权失败：{} {}（{}）", request.getMethod(), request.getRequestURI(), e.getMessage());
            writeSessionInvalid(response);
            return false;
        }
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
