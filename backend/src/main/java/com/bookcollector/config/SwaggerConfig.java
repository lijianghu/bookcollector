package com.bookcollector.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档（springdoc 1.6.15，对应 OpenAPI 3）。
 *
 * <h3>为什么是 springdoc 而不是 springfox</h3>
 * Spring Boot 2.3 时代 springfox 3.0 与 Spring MVC 5.2 的路径匹配策略有冲突
 * （{@code PathPatternParser} vs {@code AntPathMatcher}），启动即报
 * {@code documentationPluginsBootstrapper} NPE。springdoc 是 OpenAPI 3 原生实现，
 * 与 Spring Boot 2.x 的兼容性一直更干净。
 *
 * <h3>为什么声明 securityScheme</h3>
 * 34+ 个端点里有 31 个需要 token。声明之后 Swagger UI 上会多一个 <b>Authorize</b>
 * 按钮，粘一次 token 就能直接调所有接口 —— 否则手工调试每个端点都要复制粘贴 header。
 * 注意这个声明<b>只是文档层面的提示</b>，真正的校验在
 * {@link com.bookcollector.auth.TokenInterceptor}，两处不共享代码（也不该共享：
 * 一个是给人和工具看的契约，一个是运行时逻辑）。
 */
@Configuration
public class SwaggerConfig {

    private static final String SECURITY_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI bookCollectorOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("微信读书图书采集后台 API")
                        .description("第一期：图书库 / 分类榜单字典 / 采集任务 / 断点续传游标 / 仪表盘统计 / 请求审计。"
                                + "统一返回体为 ResultBean，HTTP 状态码恒为 200，业务结果看 body 里的 code。"
                                + "除 /api/auth/login 与 /api/ping** 外，所有 /api/** 都需要 token。")
                        .version("1.0.0")
                        .contact(new Contact().name("bookcollector-admin")))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                                .name(SECURITY_SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("token")
                                .description("固定 token，取值见 application.yml 的 bookcollector.auth.token")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }
}
