package com.bookcollector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 初始管理员账号的播种配置。对应 {@code application.yml} 的 {@code bookcollector.auth.*}。
 *
 * <h3>🔴 它不再是「登录校验的来源」</h3>
 * 改造前这里是写死的凭据，{@code AuthServiceImpl} 直接拿它和请求比对 ——
 * 于是「改密码要重启」「登出是假的」两个问题都无解。
 * 现在登录校验的对象是 {@code sys_user} 集合（密码存 MD5 摘要），
 * 本类退化成<b>只在首次启动、且库里还没有同名用户时用一次</b>的播种参数，
 * 由 {@code SeedRunner} 消费。
 *
 * <p>换句话说：<b>改这段配置对已存在的账号没有任何影响。</b>
 * 要改密码，改库（或删掉账号让它重新播种）。
 *
 * <h3>为什么账号密码放 yml 而不是 {@code settings} 集合</h3>
 * {@code settings} 是「用户可在界面上改的运行参数」（默认页数、请求间隔）。
 * 初始账号属于「部署配置」——它只在建库那一次有用，之后就该由数据自己说话，
 * 放进 {@code settings} 反而会让人以为改它有用。
 *
 * <p><b>⚠️ 这是一份明文的开发态凭据</b>，只因为本服务仅绑定本机 127.0.0.1、
 * 单用户使用（2.5 非功能需求）才成立。它不会被写进数据库
 * （{@code SeedRunner} 落库前先做 MD5），但会出现在 yml 里 ——
 * 一旦要对外暴露，必须换成真正的认证方案。
 */
@Component
@ConfigurationProperties(prefix = "bookcollector.auth")
public class AuthProperties {

    /** 初始管理员的登录名。为空时跳过播种（并打 WARN） */
    private String initialUsername = "admin";

    /**
     * 初始管理员的**明文**密码。
     *
     * <p>它只在播种那一刻存在：{@code SeedRunner} 会先
     * {@code Md5Util.md5(...)} 再落库，库里存的是摘要。
     */
    private String initialPassword = "admin123";

    /** 初始管理员的展示名。为空时前端回退显示登录名 */
    private String initialNickname = "管理员";

    public String getInitialUsername() {
        return initialUsername;
    }

    public void setInitialUsername(String initialUsername) {
        this.initialUsername = initialUsername;
    }

    public String getInitialPassword() {
        return initialPassword;
    }

    public void setInitialPassword(String initialPassword) {
        this.initialPassword = initialPassword;
    }

    public String getInitialNickname() {
        return initialNickname;
    }

    public void setInitialNickname(String initialNickname) {
        this.initialNickname = initialNickname;
    }
}
