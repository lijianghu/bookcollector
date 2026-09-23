package com.bookcollector.auth.service.impl;

import com.bookcollector.auth.dto.LoginUser;
import com.bookcollector.auth.entity.SysUser;
import com.bookcollector.auth.repository.SysUserRepository;
import com.bookcollector.auth.req.LoginRequest;
import com.bookcollector.auth.service.AuthService;
import com.bookcollector.auth.stp.AuthStpUtil;
import com.bookcollector.common.BizException;
import com.bookcollector.util.Md5Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link AuthService} 的实现。
 *
 * <p>登录流程对齐参考实现（{@code PybDistributorLoginServiceImpl}）的四步：
 * <b>查账号 → 比对密码摘要 → {@code StpUtil.login(id)} → 会话里存不含密码的登录 DTO</b>。
 * 差别只有密码算法（参考用 SHA1，本项目按要求用 MD5）。
 *
 * <p>「登出会真的失效」这件事由 Sa-Token 保证 —— 见 {@link #logout}。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final SysUserRepository sysUserRepository;

    public AuthServiceImpl(SysUserRepository sysUserRepository) {
        this.sysUserRepository = sysUserRepository;
    }

    @Override
    public Map<String, Object> login(LoginRequest req) {
        String username = req.getUsername() == null ? "" : req.getUsername().trim();
        String password = req.getPassword() == null ? "" : req.getPassword();

        SysUser account = sysUserRepository.findByUsername(username);

        // 刻意不区分「账号不存在」和「密码不对」—— 统一提示「用户名或密码错误」。
        // 本地服务其实没这个必要，但这条习惯成本为零，且能避免以后加审计时
        // 从错误文案里泄漏「哪些用户名是有效的」。
        // （差异只写进日志，日志是运维视角，不是攻击者视角。）
        if (account == null || !Md5Util.matches(password, account.getPassword())) {
            log.warn("登录失败：username={}（{}）", username,
                    account == null ? "账号不存在" : "密码不匹配");
            throw BizException.warn("用户名或密码错误");
        }

        // 停用判定：只有**显式** false 才算停用。
        // 用 Boolean.FALSE.equals 而不是 !account.getEnabled()：历史数据可能没有
        // 这个字段（null），后者会把 null 判成「已停用」，把所有老账号锁在门外。
        if (Boolean.FALSE.equals(account.getEnabled())) {
            log.warn("登录被拒：账号已停用 username={}", username);
            throw BizException.warn("账号已停用");
        }

        // ① 签发 token 并建立会话（落 Redis）
        AuthStpUtil.login(account.getId());

        // ② 会话里存**脱敏投影**（LoginUser 里没有 password 字段，这是刻意的）
        LoginUser loginUser = toLoginUser(account);
        AuthStpUtil.setSessionLoginUser(loginUser);

        // ③ 刷新最后登录时间。
        //    放在校验通过之后：否则「最近登录时间」会变成「最近尝试登录时间」，
        //    失去「这个账号还在用吗」的判断价值。
        //    失败不影响登录结果 —— 用户可能正好被删了，那也不该让这次登录报错。
        if (!sysUserRepository.touchLastLogin(account.getId(), new Date())) {
            log.warn("刷新 lastLoginAt 未命中任何文档（账号可能刚被删除）：id={}", account.getId());
        }

        // ④ 返回体只给 token + username —— **与改造前的键完全一致**，前端零改动。
        //    nickname / roles 不在这里给：前端登录后本来就会调 /api/auth/me，
        //    多给一份等于制造两个真相来源。
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("token", AuthStpUtil.getTokenInfo().getTokenValue());
        data.put("username", loginUser.getUsername());
        log.info("登录成功：{}", username);
        return data;
    }

    @Override
    public Map<String, Object> logout(LoginUser loginUser) {
        // ★ 与改造前的本质区别：这里真的把会话从 Redis 删掉了。
        //   改造前 token 是写死的、不会变，「登出」只能清前端 localStorage，
        //   服务端没有任何可撤销的东西。
        AuthStpUtil.logout();
        log.info("已退出登录：{}", loginUser.getUsername());

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("message", "已退出登录");
        return data;
    }

    @Override
    public Map<String, Object> me(LoginUser loginUser) {
        // 键与改造前完全一致（username / roles / nickname），前端零改动。
        // 数据来源从「写死的配置」换成了「sys_user 记录」——
        // 这是本次改造对 /me 的唯一影响，外部看不到差别。
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("username", loginUser.getUsername());
        data.put("roles", loginUser.getRoles() == null
                ? Collections.<String>emptyList() : loginUser.getRoles());
        data.put("nickname", loginUser.getNickname());
        return data;
    }

    /** {@code sys_user} → 会话里的脱敏投影。**注意这里没有任何一行碰 password** */
    private LoginUser toLoginUser(SysUser account) {
        return LoginUser.builder()
                .id(account.getId())
                .username(account.getUsername())
                .nickname(nicknameOf(account))
                .roles(account.getRoles() == null
                        ? Collections.<String>emptyList() : account.getRoles())
                .build();
    }

    /** 展示名缺失时回退成登录名 —— 前端顶栏就不会出现空白 */
    private String nicknameOf(SysUser account) {
        String nickname = account.getNickname();
        return (nickname == null || nickname.trim().isEmpty())
                ? account.getUsername() : nickname;
    }
}
