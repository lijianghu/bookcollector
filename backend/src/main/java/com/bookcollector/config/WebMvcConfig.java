package com.bookcollector.config;

import com.bookcollector.auth.TokenInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 装配：注册 token 拦截器。
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
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebMvcConfig.class);

    private final TokenInterceptor tokenInterceptor;

    public WebMvcConfig(TokenInterceptor tokenInterceptor) {
        this.tokenInterceptor = tokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tokenInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/ping",
                        "/api/ping/**");
        log.info("Token 拦截器已注册：拦截 /api/**，放行 /api/auth/login、/api/ping**");
    }
}
