package com.bookcollector.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 写死登录的账号配置。对应 {@code application.yml} 的 {@code bookcollector.auth.*}。
 *
 * <h3>为什么账号密码放 yml 而不是 {@code settings} 集合</h3>
 * {@code settings} 是「用户可在界面上改的运行参数」（默认页数、请求间隔）。
 * 账号密码属于「部署配置」，第一期明确不做权限体系（FR-A5），
 * 所以它既不该被界面改，也不该明文落库。放 yml 最直白。
 *
 * <p><b>⚠️ 这是一份明文的开发态凭据</b>，只因为本服务仅绑定本机 127.0.0.1、
 * 单用户使用（2.5 非功能需求）才成立。一旦要对外暴露，必须换成真正的认证方案。
 */
@Component
@ConfigurationProperties(prefix = "bookcollector.auth")
public class AuthProperties {

    /** 登录用户名 */
    private String username = "admin";

    /** 登录密码 */
    private String password = "admin123";

    /**
     * 登录成功后下发的固定 token。
     *
     * <p>它不是 JWT，也不带过期时间 —— 第一期就是一把「约定好的钥匙」。
     * 后端不做会话存储（无状态），所以 {@code logout} 只能由前端清 localStorage，
     * 服务端没有可撤销的东西。这是刻意的简化，不是遗漏。
     */
    private String token = "local-dev-token-please-change";

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

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
