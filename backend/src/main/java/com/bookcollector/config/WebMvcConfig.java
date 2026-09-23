package com.bookcollector.config;

import com.bookcollector.auth.extract.ExtractLoginUserHandlerResolver;
import com.bookcollector.auth.interceptor.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Spring MVC 装配：注册鉴权拦截器 + 登录用户参数解析器。
 *
 * <h3>为什么拦截范围是 {@code /api/**} 而不是 {@code /**}</h3>
 * 这样 Swagger 的 {@code /v3/api-docs}、{@code /swagger-ui/**}
 * 以及 Spring Boot 的 {@code /error} 天然不被拦截，不用手工维护白名单。
 *
 * <h3>白名单里为什么只有这两个</h3>
 * <ul>
 *   <li>{@code /api/auth/login} —— 登录本身当然不能要求先登录</li>
 *   <li>{@code /api/ping} —— 健康检查。它同时是 S0 的验收点
 *       （「不带任何头就能拿到 code:200」），也是前端启动时探测
 *       「后端到底起来没有」的手段。要求它带 token 会让排障变难：
 *       连不上时你分不清是后端没起还是 token 不对。</li>
 * </ul>
 * {@code /api/ping/biz-error} 在 {@code /api/ping/**} 下，一并放行 ——
 * 它的用途是验证异常链路，不该被鉴权挡住。
 *
 * <p><b>注意 {@code /api/auth/logout} 不在白名单里</b>：它需要登录态才有意义
 * （「谁在登出」）。Sa-Token 的 {@code logout()} 本身对未登录是幂等的，
 * 但让一个未登录的请求也能调登出接口没有任何价值，不如让它 4100。
 *
 * <h3>为什么还要注册 {@link ExtractLoginUserHandlerResolver}</h3>
 * 两件事职责不同，都要：
 * <ul>
 *   <li><b>拦截器 = 授权</b>：默认拒绝。新增一个 {@code /api/**} 接口，
 *       不加任何注解就自动受保护 —— 这是「漏加保护」这类事故的兜底</li>
 *   <li><b>解析器 = 取当前用户</b>：让 Controller 能直接拿到 {@code LoginUser}，
 *       不必每个方法都写一遍「校验 + 从会话取 + 判空」</li>
 * </ul>
 * 类比 Spring Security 的 filter chain + {@code @AuthenticationPrincipal}：
 * 前者管「能不能进」，后者管「进来的是谁」。把两者混成一个，要么漏保护，
 * 要么 Controller 里塞满样板代码。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebMvcConfig.class);

    private final AuthInterceptor authInterceptor;
    private final ExtractLoginUserHandlerResolver extractLoginUserHandlerResolver;

    public WebMvcConfig(AuthInterceptor authInterceptor,
                        ExtractLoginUserHandlerResolver extractLoginUserHandlerResolver) {
        this.authInterceptor = authInterceptor;
        this.extractLoginUserHandlerResolver = extractLoginUserHandlerResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/ping",
                        "/api/ping/**");
        log.info("鉴权拦截器已注册：拦截 /api/**，放行 /api/auth/login、/api/ping**");
    }

    /**
     * 注册 {@code @ExtractLoginUser} 的解析器。
     *
     * <p>自定义解析器会被排在 Spring 内置解析器<b>之前</b>
     * （{@code RequestMappingHandlerAdapter#getDefaultArgumentResolvers} 先放 custom 再放 builtin），
     * 所以带 {@code @ExtractLoginUser} 的 {@code LoginUser} 参数一定由我们接管，
     * 不会被内置的「查询参数绑定」或「请求体转换」抢走。
     */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {
        argumentResolvers.add(extractLoginUserHandlerResolver);
    }
}
