package com.bookcollector.auth;

import com.bookcollector.auth.dto.LoginRequest;
import com.bookcollector.common.BizException;
import com.bookcollector.common.ResultBean;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 写死登录（FR-A1 ~ FR-A4）。
 *
 * <p>不建用户表、不做角色、不做菜单权限（FR-A5）。
 * 三个端点里只有 {@code login} 在拦截器白名单内，{@code logout} / {@code me}
 * 都要求带上合法 token —— 否则「已登录」这件事无法被验证。
 */
@Tag(name = "01. 认证", description = "写死校验，不做权限体系")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthProperties properties;

    public AuthController(AuthProperties properties) {
        this.properties = properties;
    }

    @Operation(summary = "登录", description = "校验写死的账号密码，成功返回固定 token")
    @PostMapping("/login")
    public ResultBean<Map<String, Object>> login(@Validated @RequestBody LoginRequest req) {
        String username = req.getUsername() == null ? "" : req.getUsername().trim();
        String password = req.getPassword() == null ? "" : req.getPassword();

        if (!properties.getUsername().equals(username)
                || !properties.getPassword().equals(password)) {
            // 刻意不区分「用户名错」和「密码错」——虽然本地服务没这个必要，
            // 但这条习惯成本为零，且能避免以后加审计时泄漏有效用户名。
            log.warn("登录失败：username={}", username);
            throw new BizException(ResultBean.code_warn, "用户名或密码错误");
        }

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("token", properties.getToken());
        data.put("username", properties.getUsername());
        log.info("登录成功：{}", username);
        return ResultBean.ok(data);
    }

    @Operation(summary = "退出登录", description = "服务端无会话，这里只是语义上的确认；前端负责清 localStorage")
    @PostMapping("/logout")
    public ResultBean<Map<String, Object>> logout() {
        // token 是写死的、无状态的，服务端没有可撤销的东西。
        // 这里不假装做点什么（比如「把 token 加黑名单」）—— 那会引入一份
        // 需要持久化、需要过期清理的状态，而它保护不了任何东西（token 本身不会变）。
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("message", "已退出登录");
        return ResultBean.ok(data);
    }

    @Operation(summary = "当前用户信息", description = "供前端刷新页面后恢复登录态")
    @GetMapping("/me")
    public ResultBean<Map<String, Object>> me() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("username", properties.getUsername());
        // 第一期只有一个写死的账号，角色列表是给前端菜单渲染留的占位。
        // 不要在这里返回 token —— 前端已经在 localStorage 里有了，回传没有收益。
        data.put("roles", Arrays.asList("admin"));
        data.put("nickname", "管理员");
        return ResultBean.ok(data);
    }
}
