package com.bookcollector.auth.service;

import com.bookcollector.auth.dto.LoginUser;
import com.bookcollector.auth.req.LoginRequest;

import java.util.Map;

/**
 * 登录鉴权（FR-A1 ~ FR-A4）。
 *
 * <h3>与改造前的差别</h3>
 * 原来账号密码来自 {@code application.yml}，校验通过返回一个<b>固定 token</b>；
 * 现在账号密码来自 {@code sys_user} 集合（密码存 MD5 摘要），
 * 校验通过由 Sa-Token 签发 token 并建立会话（落 Redis）。由此得到两个
 * 原来做不到的能力：
 * <ul>
 *   <li><b>登出是真的</b> —— {@link #logout} 会把会话删掉，token 立即失效</li>
 *   <li><b>改密码不用重启</b> —— 校验读库，改库即生效</li>
 * </ul>
 *
 * <h3>仍然不做权限体系（FR-A5）</h3>
 * {@code roles} 只是给前端菜单渲染留的占位，<b>后端不做任何角色判断</b>。
 * 所有登录用户能看到的、能调的都一样。见 {@code auth/AGENTS.md} 铁律 8。
 *
 * <h3>三个方法里只有 {@link #login} 在白名单内</h3>
 * {@link #logout} 与 {@link #me} 都要求带合法 token —— 否则「已登录」这件事
 * 无法被验证（而且 {@code logout} 本来就需要知道「谁在登出」）。
 *
 * @see com.bookcollector.auth.service.impl.AuthServiceImpl
 */
public interface AuthService {

    /**
     * 登录。校验 {@code sys_user} 里的账号密码，成功签发 token 并建立会话。
     *
     * @return {@code token} / {@code username} —— <b>与改造前完全一致的键</b>，
     *         所以前端不需要改（前端只读这两个字段）
     */
    Map<String, Object> login(LoginRequest req);

    /**
     * 退出登录。<b>真的删掉服务端会话</b>（Redis），token 立即失效。
     *
     * @param loginUser 当前登录用户，由 {@code @ExtractLoginUser} 注入。
     *                  它同时也是一道鉴权：拿不到用户就走不到这里
     * @return {@code message}
     */
    Map<String, Object> logout(LoginUser loginUser);

    /**
     * 当前用户信息，供前端刷新页面后恢复登录态。
     *
     * @return {@code username} / {@code roles} / {@code nickname}
     *         —— 与改造前完全一致的键，且<b>不含 token</b>
     *         （前端 localStorage 里已经有了，回传没有收益）
     */
    Map<String, Object> me(LoginUser loginUser);
}
