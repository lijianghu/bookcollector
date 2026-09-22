package com.bookcollector.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.validation.constraints.NotBlank;

/**
 * 登录请求体。
 *
 * <p>用 {@code @NotBlank} 而不是自己判空，是为了让「漏传字段」这件事走
 * {@code GlobalExceptionHandler} 的 {@code MethodArgumentNotValidException} 分支，
 * 统一返回 {@code code=400} + 可读 message —— 与其它模块的参数校验表现一致。
 */
@Schema(description = "登录请求")
public class LoginRequest {

    @Schema(description = "用户名", example = "admin", required = true)
    @NotBlank(message = "不能为空")
    private String username;

    @Schema(description = "密码", example = "admin123", required = true)
    @NotBlank(message = "不能为空")
    private String password;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
