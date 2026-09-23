package com.bookcollector.auth.controller;

import com.bookcollector.auth.dto.LoginUser;
import com.bookcollector.auth.extract.ExtractLoginUser;
import com.bookcollector.auth.req.LoginRequest;
import com.bookcollector.auth.service.AuthService;
import com.bookcollector.common.ResultBean;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 登录鉴权（FR-A1 ~ FR-A4）。
 *
 * <p>三个端点里只有 {@code login} 在拦截器白名单内，{@code logout} / {@code me}
 * 都要求带上合法 token —— 否则「已登录」这件事无法被验证。
 *
 * <p>这一层是<b>极薄的转发</b>：校验与组装都在 {@link AuthService} 里。
 *
 * <h3>{@code @ExtractLoginUser} 的用法</h3>
 * {@code logout} / {@code me} 都需要「当前是谁」。它们不自己去取，而是在方法
 * 参数上声明 {@code @ExtractLoginUser LoginUser}，由
 * {@link com.bookcollector.auth.extract.ExtractLoginUserHandlerResolver}
 * 完成「校验登录态 + 注入用户 + 校验身份一致」三件事。
 *
 * <p><b>⚠️ 必须加 {@code @Parameter(hidden = true)}</b>：这个参数是框架注入的，
 * 不是客户端传的。不加的话 springdoc 会在 Swagger UI 上渲染出一个
 * 「必填的 loginUser 输入框」，误导使用者。
 *
 * <p>注意 {@code login} 的方法签名<b>没有</b>这个参数 —— 它本身就在白名单里，
 * 拿不到也不该拿登录用户。
 */
@Tag(name = "01. 认证", description = "基于 Sa-Token 的登录鉴权（sys_user + MD5）；roles 不参与鉴权")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    @Operation(summary = "登录",
            description = "校验 sys_user 集合里的账号密码（密码存 MD5 摘要），成功返回 token。"
                    + "账号密码来自首次启动时播种的初始管理员（application.yml 的 bookcollector.auth.initial-*），"
                    + "之后改密码请直接改库。")
    @PostMapping("/login")
    public ResultBean<Map<String, Object>> login(@Validated @RequestBody LoginRequest req) {
        return ResultBean.ok(service.login(req));
    }

    @Operation(summary = "退出登录",
            description = "真的删掉服务端会话（Redis），token 立即失效 —— 不再是「只清前端 localStorage」")
    @PostMapping("/logout")
    public ResultBean<Map<String, Object>> logout(
            @Parameter(hidden = true) @ExtractLoginUser LoginUser loginUser) {
        return ResultBean.ok(service.logout(loginUser));
    }

    @Operation(summary = "当前用户信息", description = "供前端刷新页面后恢复登录态")
    @GetMapping("/me")
    public ResultBean<Map<String, Object>> me(
            @Parameter(hidden = true) @ExtractLoginUser LoginUser loginUser) {
        return ResultBean.ok(service.me(loginUser));
    }
}
