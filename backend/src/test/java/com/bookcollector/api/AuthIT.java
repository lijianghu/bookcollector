package com.bookcollector.api;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate;
import com.bookcollector.auth.entity.SysUser;
import com.bookcollector.auth.repository.SysUserRepository;
import com.bookcollector.util.Md5Util;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 登录鉴权（Sa-Token + {@code sys_user} + Redis）的专项验收。
 *
 * <h3>为什么这些断言值得单独一个类</h3>
 * 这个改造里有三处失败是「<b>不报错</b>的」，只靠「登录能通」的冒烟测试发现不了：
 *
 * <table border="1">
 *   <tr><th>静默失败</th><th>表面现象</th><th>本类的对应断言</th></tr>
 *   <tr>
 *     <td>Sa-Token 没接到 Redis DAO，退回内存实现</td>
 *     <td>登录、鉴权<b>全都正常</b>，只是 JVM 一重启就掉登录态，
 *         而且「登出」只在本进程内有效</td>
 *     <td>{@link #saTokenDaoIsRedisBacked()}</td>
 *   </tr>
 *   <tr>
 *     <td>Jackson 反序列化把会话里的 DTO 退化成 {@code LinkedHashMap}</td>
 *     <td>登录接口返回 200，但<b>下一个请求就 4100</b>，
 *         报错信息是「登录信息已失效」—— 看起来像 token 没存对</td>
 *     <td>{@link #sessionHoldsTypedLoginUser()}</td>
 *   </tr>
 *   <tr>
 *     <td>密码被明文落库</td>
 *     <td>功能完全正常，没有任何报错</td>
 *     <td>{@link #passwordIsStoredAsMd5NotPlaintext()}</td>
 *   </tr>
 * </table>
 *
 * <p>所以本类不做「登录能成功吗」这种冒烟断言（{@code ApiContractTest} 已经覆盖），
 * 只盯这三条「错了也不会红」的地方。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("S8 · 登录鉴权（Sa-Token + sys_user + Redis）")
class AuthIT {

    private static final String TOKEN_HEADER = "Authorization";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SysUserRepository sysUserRepository;

    @Value("${bookcollector.auth.initial-username}")
    private String username;

    @Value("${bookcollector.auth.initial-password}")
    private String password;

    private final ObjectMapper json = new ObjectMapper();

    // ==================================================================
    // 静默失败 ①：DAO 到底是不是 Redis 的
    // ==================================================================

    /**
     * 🔴 这是整个改造里最容易「配了但没生效」的一处。
     *
     * <p>Sa-Token 默认用内存 DAO。如果 {@code sa-token-redis-jackson} 的自动配置
     * 因为任何原因没被加载（比如 Spring Boot 版本差异导致 {@code spring.factories}
     * 没被读到），Sa-Token 会<b>安静地</b>用内存实现 ——
     * 登录、鉴权、登出<b>全都看起来正常</b>，只是：
     * <ul>
     *   <li>JVM 一重启，所有登录态消失（用户会以为是「偶尔要重新登录」）</li>
     *   <li>{@code logout} 只在当前进程内有效</li>
     *   <li>引入 Redis 这件事本身白做了</li>
     * </ul>
     * 没有任何日志会提示这件事，所以必须用断言把它钉死。
     *
     * <p>本项目已核实：{@code sa-token-spring-boot-starter} 与
     * {@code sa-token-redis-template} 的 jar 里<b>同时</b>有
     * {@code META-INF/spring.factories}（Spring Boot 2.3 读这个）
     * 和 {@code META-INF/spring/...AutoConfiguration.imports}，
     * 所以 Boot 2.3 能正常加载。这条断言就是那个结论的可执行版本。
     */
    @Test
    @DisplayName("Sa-Token 的 DAO 是 Redis 实现，而不是静默退回的内存实现")
    void saTokenDaoIsRedisBacked() {
        SaTokenDao dao = SaManager.getSaTokenDao();
        assertNotNull(dao, "SaManager 里必须有 SaTokenDao");
        assertTrue(dao instanceof SaTokenDaoForRedisTemplate,
                "Sa-Token 的 DAO 必须是 Redis 实现，实际是 " + dao.getClass().getName()
                        + " —— 说明 sa-token-redis-jackson 的自动配置没生效，"
                        + "此时登录态只存在内存里，JVM 重启即失效，且不会有任何报错");
    }

    // ==================================================================
    // 静默失败 ②：会话里的对象类型
    // ==================================================================

    /**
     * 会话里存的是 {@code LoginUser}，不是 {@code LinkedHashMap}。
     *
     * <p>Sa-Token 的会话被序列化成 JSON 存进 Redis。如果 Jackson 没有打开
     * 多态类型信息，取回来的是 {@code LinkedHashMap} —— 而
     * {@code ExtractLoginUserHandlerResolver} 的身份一致性校验要求
     * {@code instanceof LoginUser}，于是会抛 4100。
     *
     * <p>换句话说：<b>本用例返回 200 这件事本身，就证明了类型没有丢</b>。
     * 这正是它放在这里的原因 —— 失败时的现象（下一个请求 4100）与根因
     * （Jackson 配置）之间没有任何直观联系，很容易被误判成「token 没存对」。
     *
     * <p>本项目已核实：{@code sa-token-jackson} 的
     * {@code SaJsonTemplateForJackson} 构造时会执行
     * {@code activateDefaultTyping(..., DefaultTyping.NON_FINAL, As.PROPERTY)}，
     * 所以非 final 类型会带 {@code @class} 往返。前提是 DTO 有无参构造器。
     */
    @Test
    @DisplayName("会话里的登录用户是 LoginUser 类型（Jackson 未把类型退化成 Map）")
    void sessionHoldsTypedLoginUser() throws Exception {
        String token = login();

        // 换一次请求：这一次会话必须从 Redis 反序列化出来，
        // 而不是复用登录那次请求的内存对象
        JsonNode me = readJson(mockMvc.perform(get("/api/auth/me")
                .header(TOKEN_HEADER, "Bearer " + token)).andReturn());

        assertEquals(200, me.get("code").asInt(),
                "会话反序列化后应能取回 LoginUser。若这里是 4100，"
                        + "说明会话里的对象类型退化成了 Map（检查 LoginUser 的无参构造器、"
                        + "以及 sa-token-jackson 是否在依赖里）");
        assertEquals(username, me.get("data").get("username").asText());
        assertTrue(me.get("data").get("roles").isArray(), "roles 应为数组");
        assertFalse(me.get("data").get("roles").isEmpty(), "播种账号应有 admin 角色（占位）");
    }

    // ==================================================================
    // 静默失败 ③：密码落库形态
    // ==================================================================

    @Test
    @DisplayName("sys_user 里存的是 MD5 摘要，不是明文")
    void passwordIsStoredAsMd5NotPlaintext() {
        SysUser account = sysUserRepository.findByUsername(username);
        assertNotNull(account, "初始管理员应由 SeedRunner 播种进 sys_user");

        assertFalse(password.equals(account.getPassword()),
                "🔴 库里绝不能存明文密码");
        assertEquals(Md5Util.md5(password), account.getPassword(),
                "存的应是 initial-password 的 MD5 摘要");
        assertEquals(32, account.getPassword().length(), "MD5 十六进制摘要应为 32 位");
        assertTrue(account.getPassword().matches("[0-9a-f]{32}"),
                "摘要应为小写十六进制，实际：" + account.getPassword());
        assertEquals(Boolean.TRUE, account.getEnabled(), "播种账号应默认启用");
    }

    /**
     * 密码摘要不能从任何响应里漏出去。
     *
     * <p>{@code LoginUser} 刻意不复用 {@code SysUser}、而是重新声明一遍字段，
     * 就是为了这件事 —— 一旦有人「图省事」把它换成 {@code SysUser}，
     * 密码摘要就会进会话（Redis）和 {@code /api/auth/me} 的响应体。
     * 这条断言是那个设计决定的守卫。
     */
    @Test
    @DisplayName("登录 / me 的响应里不含密码，也不含密码摘要")
    void responsesNeverLeakPassword() throws Exception {
        String token = login();
        String hash = Md5Util.md5(password);

        JsonNode loginBody = readJson(mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginPayload())).andReturn());
        assertNoPasswordTrace(loginBody.toString(), hash, "登录响应");

        JsonNode me = readJson(mockMvc.perform(get("/api/auth/me")
                .header(TOKEN_HEADER, "Bearer " + token)).andReturn());
        assertNoPasswordTrace(me.toString(), hash, "/api/auth/me 响应");
    }

    // ==================================================================
    // 登录失败的两条分支
    // ==================================================================

    /**
     * 「账号不存在」与「密码不对」必须给出<b>同一个</b> message。
     *
     * <p>这是 {@code auth/AGENTS.md} 铁律 5 的可执行版本。本地服务其实没这个必要，
     * 但这条习惯成本为零，且能避免以后加审计时从错误文案里泄漏
     * 「哪些用户名是有效的」。
     */
    @Test
    @DisplayName("账号不存在 / 密码错误 → 都是 400 且 message 完全一致（不泄漏有效用户名）")
    void loginFailureDoesNotRevealWhichPartIsWrong() throws Exception {
        JsonNode wrongPassword = readJson(mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"definitely-not-it\"}"))
                .andReturn());
        JsonNode unknownUser = readJson(mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"s8it-no-such-user\",\"password\":\"whatever\"}"))
                .andReturn());

        assertEquals(400, wrongPassword.get("code").asInt(), "密码错误应为 400");
        assertEquals(400, unknownUser.get("code").asInt(), "账号不存在也应为 400（不是 404）");
        assertEquals(wrongPassword.get("message").asText(), unknownUser.get("message").asText(),
                "两种失败的 message 必须一致，否则能据此枚举出有效用户名");
        // 不能把「账号不存在」这种内情写进对外文案
        assertFalse(unknownUser.get("message").asText().contains("不存在"),
                "message 不应暴露账号是否存在：" + unknownUser.get("message").asText());
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private String loginPayload() {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    /** 真实登录并返回 token，同时断言登录本身是成功的 */
    private String login() throws Exception {
        JsonNode body = readJson(mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginPayload())).andReturn());
        assertEquals(200, body.get("code").asInt(),
                "登录应成功（code=" + body.get("code").asInt()
                        + " message=" + body.get("message").asText() + "）");
        String token = body.get("data").get("token").asText();
        assertFalse(token.trim().isEmpty(), "登录必须返回非空 token");
        return token;
    }

    private void assertNoPasswordTrace(String payload, String hash, String scene) {
        assertFalse(payload.contains("\"password\""), scene + " 出现了 password 字段：" + payload);
        assertFalse(payload.contains(hash), scene + " 泄漏了密码摘要：" + payload);
        assertFalse(payload.contains(password), scene + " 泄漏了明文密码：" + payload);
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertNotNull(content, "响应体不应为 null");
        assertFalse(content.isEmpty(), "响应体不应为空");
        return json.readTree(content);
    }
}
