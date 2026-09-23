package com.bookcollector.auth.stp;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpLogic;
import com.bookcollector.auth.dto.LoginUser;

/**
 * 本项目的 Sa-Token 门面 —— 登录态的<b>唯一入口</b>。
 *
 * <h3>🔴 为什么不用 {@code cn.dev33.satoken.stp.StpUtil}</h3>
 * {@code StpUtil} 内部用的是 loginType = {@code "login"}，这是一个**全局共享**的名字。
 * 本项目与其它本地项目共用同一个 Redis 实例（{@code database: 0}），
 * 如果两个应用都用默认的 {@code StpUtil}，它们的 token 和会话会落在
 * 同一批 key（{@code satoken:login:...}）上 —— 表现为「登录了 A 应用，
 * B 应用也变成已登录」这种极难排查的串号。
 *
 * <p>所以这里显式建一个自己的 {@link StpLogic}，loginType 用项目专属的
 * {@link #LOGIN_TYPE}。Sa-Token 的所有 Redis key 都会带上它，天然隔离。
 *
 * <h3>⚠️ 由此带来的一条纪律</h3>
 * <b>业务代码里不要直接 import {@code StpUtil}。</b>
 * 它编译得过、但登录态和本类完全不相干 —— {@code StpUtil.checkLogin()}
 * 永远抛未登录，而 {@code StpUtil.login(id)} 登录的用户本类也认不出来。
 * 需要登录态就调本类的方法。这是「多端隔离」换来的唯一代价，值。
 *
 * <h3>为什么是 {@code final} + 私有构造</h3>
 * 纯静态门面，不该被继承或被 new 出来。方法全部委托给
 * {@link #stpLogic}，本类自己<b>不持有任何状态</b>。
 *
 * @see com.bookcollector.auth.extract.ExtractLoginUserHandlerResolver
 */
public final class AuthStpUtil {

    /**
     * 登录类型标识 —— 同时是 Redis key 的命名空间。
     *
     * <p>取值带上项目名（而不是 {@code "admin"}）就是为了避免与其它项目撞名，
     * 理由见类注释。
     */
    public static final String LOGIN_TYPE = "bookcollector-admin";

    /**
     * 会话里存登录用户的 key。
     *
     * <p>对齐参考实现（{@code AuthConstants.SESSION_LOGIN_USER_KEY}）的命名，
     * 方便以后跨项目看代码时一眼认出是同一个约定。
     */
    public static final String SESSION_LOGIN_USER_KEY = "loginUser";

    /** 全局唯一的 StpLogic 实例。Sa-Token 要求同一个 loginType 只应有一个实例 */
    public static final StpLogic stpLogic = new StpLogic(LOGIN_TYPE);

    private AuthStpUtil() {
    }

    // ------------------------------------------------------------------
    // 登录 / 登出
    // ------------------------------------------------------------------

    /**
     * 登录：生成 token 并建立会话。
     *
     * <p>有效期等参数全部来自 {@code application.yml} 的 {@code sa-token.*}
     * （见 {@code application.yml} 里的注释），这里不硬编码任何时长。
     */
    public static void login(String userId) {
        stpLogic.login(userId);
    }

    /**
     * 登出：<b>真的</b>把会话从 Redis 删掉。
     *
     * <p>与改造前「写死 token、logout 只能清前端 localStorage」的关键差别就在这里 ——
     * 现在服务端有可撤销的东西了。
     *
     * <p>未登录时调用<b>不抛异常</b>（Sa-Token 内部先判 {@code isLogin()}），
     * 所以重复登出是安全的。
     */
    public static void logout() {
        stpLogic.logout();
    }

    // ------------------------------------------------------------------
    // 登录态查询
    // ------------------------------------------------------------------

    /**
     * 校验登录态，未登录抛 {@link cn.dev33.satoken.exception.NotLoginException}。
     *
     * <p>拦截器会捕获它并翻译成 {@code code=4100}；服务层里直接用也可以，
     * {@code GlobalExceptionHandler} 有兜底分支。
     */
    public static void checkLogin() {
        stpLogic.checkLogin();
    }

    public static boolean isLogin() {
        return stpLogic.isLogin();
    }

    /**
     * 当前登录用户 ID。
     *
     * <p>未登录时抛 {@code NotLoginException}（不是返回 null）——
     * 调用前应先 {@link #checkLogin()}。
     */
    public static String getUserId() {
        return stpLogic.getLoginIdAsString();
    }

    public static SaTokenInfo getTokenInfo() {
        return stpLogic.getTokenInfo();
    }

    // ------------------------------------------------------------------
    // 会话读写
    // ------------------------------------------------------------------

    /**
     * 当前请求对应的会话。
     *
     * <p>未登录时抛 {@code NotLoginException}，所以只该在 {@link #checkLogin()} 之后调。
     */
    public static SaSession getSession() {
        return stpLogic.getSession();
    }

    /** 把登录用户写进会话。登录成功后立刻调用 */
    public static void setSessionLoginUser(LoginUser loginUser) {
        getSession().set(SESSION_LOGIN_USER_KEY, loginUser);
    }

    /**
     * 从会话里取登录用户。
     *
     * @return 会话里没有、或存的不是 {@link LoginUser} 时返回 {@code null}
     *         （由调用方决定怎么处理，本方法不抛业务异常）
     */
    public static LoginUser getSessionLoginUser() {
        Object value = getSession().get(SESSION_LOGIN_USER_KEY);
        return value instanceof LoginUser ? (LoginUser) value : null;
    }
}
