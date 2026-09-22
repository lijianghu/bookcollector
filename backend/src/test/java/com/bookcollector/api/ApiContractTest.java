package com.bookcollector.api;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * S4 的接口契约测试 —— 对应 S4 的 5 条验收标准。
 *
 * <h3>为什么是 {@code *Test} 而不是 {@code *IT}</h3>
 * 全程走 MockMvc，<b>不起真端口、不发真网络请求</b>（只读 Mongo 校验存量数据），
 * 所以能进 {@code mvn test} 的默认扫描范围。这样它会在每次构建时跑，
 * 而不是像 IT 那样「想起来才跑一次」—— 契约测试的价值恰恰在于「天天跑」。
 *
 * <h3>5 条验收标准 → 5 个测试方法</h3>
 * <table border="1">
 *   <tr><th>验收标准</th><th>测试方法</th></tr>
 *   <tr><td>端点齐全</td><td>{@link #allBusinessEndpointsAreRegistered()}</td></tr>
 *   <tr><td>不带头 → code=4100</td><td>{@link #protectedEndpointsRequireToken()}</td></tr>
 *   <tr><td>非法参数 → code=400 且可读</td><td>{@link #invalidParametersReturnReadable400()}</td></tr>
 *   <tr><td>响应体不含 ex / true / false</td><td>{@link #responseBodyNeverLeaksInternalFields()}</td></tr>
 *   <tr><td>curl 抽查每模块</td><td>{@link #everyModuleAnswersWithCode200()}</td></tr>
 * </table>
 *
 * <h3>端点清点为什么读 {@code RequestMappingHandlerMapping} 而不是数注解</h3>
 * 数源码里的 {@code @GetMapping} 只能证明「写了注解」，不能证明
 * 「Spring 真的把它注册成路由了」—— 路径写重复、类上漏了 {@code @RequestMapping}
 * 都会让注解存在但路由缺失。读 handler mapping 是直接问 Spring
 * 「你到底注册了哪些」，这才是「端点齐全」的真实含义。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("S4 · 接口契约（离线）")
class ApiContractTest {

    private static final String TOKEN_HEADER = "Authorization";

    /** 需要 token 的业务端点，每个模块至少抽一个 */
    private static final List<String> PROTECTED_PATHS = Arrays.asList(
            "/api/auth/me",
            "/api/books",
            "/api/taxonomy/categories",
            "/api/cursors",
            "/api/tasks",
            "/api/runs",
            "/api/stats/overview",
            "/api/requests");

    /**
     * 35 个业务端点（34 个来自 5.2 端点清单 + 1 个 {@code /api/tasks/{id}/retry}）。
     *
     * <p>retry 不在原清单里，但 FR-D7 明确要求「重试失败的任务」，
     * 而 {@code TaskService.retry} 在 S3 就实现了。不暴露它等于让那段代码成为死代码。
     */
    private static final String[] EXPECTED_ENDPOINTS = {
            // auth (3)
            "POST /api/auth/login",
            "POST /api/auth/logout",
            "GET /api/auth/me",
            // books (5)
            "GET /api/books",
            "GET /api/books/{bookId}",
            "PUT /api/books/{bookId}",
            "DELETE /api/books/{bookId}",
            "POST /api/books/batch-delete",
            // taxonomy (8)
            "GET /api/taxonomy/categories",
            "POST /api/taxonomy/categories",
            "PUT /api/taxonomy/categories/{id}",
            "DELETE /api/taxonomy/categories/{id}",
            "GET /api/taxonomy/rankings",
            "POST /api/taxonomy/rankings",
            "PUT /api/taxonomy/rankings/{id}",
            "DELETE /api/taxonomy/rankings/{id}",
            // cursors (3)
            "GET /api/cursors",
            "PUT /api/cursors",
            "DELETE /api/cursors/{id}",
            // tasks (10)
            "GET /api/tasks",
            "POST /api/tasks",
            "GET /api/tasks/{id}",
            "PUT /api/tasks/{id}",
            "DELETE /api/tasks/{id}",
            "POST /api/tasks/{id}/start",
            "POST /api/tasks/{id}/pause",
            "POST /api/tasks/{id}/resume",
            "POST /api/tasks/{id}/cancel",
            "POST /api/tasks/{id}/retry",
            // runs (3)
            "GET /api/runs",
            "GET /api/runs/{id}",
            "GET /api/runs/{id}/logs",
            // stats (2)
            "GET /api/stats/overview",
            "GET /api/stats/charts",
            // audit (1)
            "GET /api/requests",
    };

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Value("${bookcollector.auth.token}")
    private String token;

    private final ObjectMapper json = new ObjectMapper();

    // ==================================================================
    // 验收标准 1：端点齐全
    // ==================================================================

    @Test
    @DisplayName("验收①：35 个业务端点全部注册成功")
    void allBusinessEndpointsAreRegistered() {
        Set<String> actual = registeredEndpoints();

        Set<String> missing = new LinkedHashSet<String>();
        for (String expected : EXPECTED_ENDPOINTS) {
            if (!actual.contains(expected)) {
                missing.add(expected);
            }
        }
        assertTrue(missing.isEmpty(), "以下端点未注册：" + missing);

        // 反向检查：业务前缀下不能有清单之外的多余端点（防止「悄悄多了一个接口」）
        Set<String> unexpected = new LinkedHashSet<String>();
        for (String endpoint : actual) {
            String path = endpoint.substring(endpoint.indexOf(' ') + 1);
            if (isBusinessPath(path) && !Arrays.asList(EXPECTED_ENDPOINTS).contains(endpoint)) {
                unexpected.add(endpoint);
            }
        }
        assertTrue(unexpected.isEmpty(), "出现了清单之外的业务端点：" + unexpected);

        assertEquals(EXPECTED_ENDPOINTS.length, countBusinessEndpoints(actual),
                "业务端点数量应为 " + EXPECTED_ENDPOINTS.length);
    }

    // ==================================================================
    // 验收标准 2：不带头 → code=4100
    // ==================================================================

    @Test
    @DisplayName("验收②：不带头调受保护端点 → HTTP 200 + code=4100")
    void protectedEndpointsRequireToken() throws Exception {
        for (String path : PROTECTED_PATHS) {
            MvcResult result = mockMvc.perform(get(path)).andReturn();

            // 关键：HTTP 状态码必须是 200。前端 axios 拦截器只看 body 里的 code，
            // 如果这里返回 401，前端会走到「HTTP 层错误」分支，弹出一句
            // 「请求异常：HTTP 401」而不是「身份已失效，请重新登录」
            assertEquals(200, result.getResponse().getStatus(),
                    path + " 的 HTTP 状态码应为 200（业务结果看 body.code）");

            JsonNode body = readJson(result);
            assertEquals(4100, body.get("code").asInt(), path + " 未带 token 应返回 code=4100");
            assertNotNull(body.get("message"), path + " 的错误响应应带 message");
            assertFalse(body.get("message").asText().isEmpty(), path + " 的 message 不应为空");
        }
    }

    @Test
    @DisplayName("验收②补：错误 token / 只带前缀 → 同样 4100")
    void wrongTokenIsRejected() throws Exception {
        assertEquals(4100, codeOf(get("/api/books").header(TOKEN_HEADER, "Bearer wrong-token")));
        assertEquals(4100, codeOf(get("/api/books").header(TOKEN_HEADER, "Bearer ")));
        assertEquals(4100, codeOf(get("/api/books").header(TOKEN_HEADER, token + "x")));
        // 备用头也不能少校验
        assertEquals(4100, codeOf(get("/api/books").header("X-Token", "nope")));
    }

    @Test
    @DisplayName("验收②补：白名单端点无需 token（ping / login）")
    void publicEndpointsAreOpen() throws Exception {
        assertEquals(200, codeOf(get("/api/ping")), "健康检查必须免鉴权，否则排障时分不清是没起来还是 token 不对");
        // 登录接口本身要求带 token 的话就永远登不进去；用错密码应得到 400 而不是 4100
        assertEquals(400, codeOf(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"wrong\"}")));
    }

    @Test
    @DisplayName("登录成功返回写死的 token，且 /api/auth/me 可用它访问")
    void loginReturnsConfiguredToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andReturn();
        JsonNode body = readJson(result);
        assertEquals(200, body.get("code").asInt());
        assertEquals(token, body.get("data").get("token").asText(), "返回的 token 应与配置一致");

        // 用拿到的 token 访问 /api/auth/me
        JsonNode me = readJson(mockMvc.perform(get("/api/auth/me")
                .header(TOKEN_HEADER, "Bearer " + token)).andReturn());
        assertEquals(200, me.get("code").asInt());
        assertEquals("admin", me.get("data").get("username").asText());
    }

    // ==================================================================
    // 验收标准 3：非法参数 → code=400 且 message 可读
    // ==================================================================

    @Test
    @DisplayName("验收③：非法参数 → code=400，message 可读")
    void invalidParametersReturnReadable400() throws Exception {
        // (a) 查询参数类型不对（minRating 声明为 Integer，传字符串）
        JsonNode badQuery = readJson(mockMvc.perform(get("/api/books")
                .header(TOKEN_HEADER, "Bearer " + token)
                .param("minRating", "not-a-number")).andReturn());
        assertEquals(400, badQuery.get("code").asInt(), "类型不匹配应返回 400");
        assertReadable(badQuery, "minRating");

        // (b) @Valid 校验失败：登录请求体缺字段
        JsonNode badBody = readJson(mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andReturn());
        assertEquals(400, badBody.get("code").asInt(), "缺少必填字段应返回 400");
        assertReadable(badBody, "username");
        // 多字段同时校验失败时，message 必须稳定 —— Hibernate Validator 不保证
        // getFieldErrors() 的顺序，不排序的话同一个请求刷新两次会给出不同提示
        JsonNode badBodyAgain = readJson(mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andReturn());
        assertEquals(badBody.get("message").asText(), badBodyAgain.get("message").asText(),
                "同样的非法输入必须得到同样的 message（校验顺序不能是随机的）");

        // (c) 请求体不是合法 JSON
        JsonNode brokenJson = readJson(mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ this is not json")).andReturn());
        assertEquals(400, brokenJson.get("code").asInt(), "非法 JSON 应返回 400");
        assertReadable(brokenJson, null);

        // (d) 业务校验失败：不存在的图书 → 404（而不是 200 + null）
        JsonNode notFound = readJson(mockMvc.perform(get("/api/books/definitely-not-exists")
                .header(TOKEN_HEADER, "Bearer " + token)).andReturn());
        assertEquals(404, notFound.get("code").asInt(), "不存在的图书应返回 404");
        assertReadable(notFound, "definitely-not-exists");

        // (e) 批量删除空列表 → 400
        JsonNode emptyBatch = readJson(mockMvc.perform(post("/api/books/batch-delete")
                .header(TOKEN_HEADER, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bookIds\":[]}")).andReturn());
        assertEquals(400, emptyBatch.get("code").asInt(), "空 bookIds 应返回 400");
    }

    // ==================================================================
    // 验收标准 4：响应体不含 ex / true / false
    // ==================================================================

    @Test
    @DisplayName("验收④：响应体不含 ex / true / false 字段（B1/B2 验证）")
    void responseBodyNeverLeaksInternalFields() throws Exception {
        // 成功响应
        JsonNode ok = readJson(mockMvc.perform(get("/api/books")
                .header(TOKEN_HEADER, "Bearer " + token)).andReturn());
        assertEquals(200, ok.get("code").asInt());
        assertNoLeakedFields(ok, "成功响应");

        // 失败响应（业务异常）—— 异常路径最容易把 Throwable 序列化出去
        JsonNode bizError = readJson(mockMvc.perform(get("/api/ping/biz-error")).andReturn());
        assertEquals(400, bizError.get("code").asInt());
        assertNoLeakedFields(bizError, "业务异常响应");

        // 鉴权失败响应（由 TokenInterceptor 自己写 JSON，不走异常处理器）
        JsonNode sessionInvalid = readJson(mockMvc.perform(get("/api/books")).andReturn());
        assertEquals(4100, sessionInvalid.get("code").asInt());
        assertNoLeakedFields(sessionInvalid, "鉴权失败响应");

        // 未捕获异常响应（GlobalExceptionHandler 兜底分支）
        JsonNode unknown = readJson(mockMvc.perform(get("/api/runs/not-an-id")
                .header(TOKEN_HEADER, "Bearer " + token)).andReturn());
        assertNoLeakedFields(unknown, "兜底异常响应");
    }

    // ==================================================================
    // 验收标准 5：每个模块都能返回 code=200
    // ==================================================================

    @Test
    @DisplayName("验收⑤：抽查每个模块，全部返回 code=200")
    void everyModuleAnswersWithCode200() throws Exception {
        // GET 类端点逐个抽查
        List<String> gets = Arrays.asList(
                "/api/auth/me",
                "/api/books",
                "/api/taxonomy/categories",
                "/api/taxonomy/rankings",
                "/api/cursors",
                "/api/tasks",
                "/api/runs",
                "/api/stats/overview",
                "/api/stats/charts",
                "/api/requests");
        for (String path : gets) {
            MockHttpServletRequestBuilder req = get(path).header(TOKEN_HEADER, "Bearer " + token);
            JsonNode body = readJson(mockMvc.perform(req).andReturn());
            assertEquals(200, body.get("code").asInt(),
                    path + " 应返回 code=200，实际 code=" + body.get("code").asInt()
                            + " message=" + body.get("message").asText());
            assertNoLeakedFields(body, path);
        }

        // 写类端点抽查：登出（无副作用）
        JsonNode logout = readJson(mockMvc.perform(post("/api/auth/logout")
                .header(TOKEN_HEADER, "Bearer " + token)).andReturn());
        assertEquals(200, logout.get("code").asInt(), "登出应返回 code=200");
    }

    // ==================================================================
    // 统计口径（FR-F1 / FR-F2 的结构性断言）
    // ==================================================================

    @Test
    @DisplayName("仪表盘：4 个指标卡字段齐全，4 张图结构正确")
    void dashboardHasExpectedShape() throws Exception {
        JsonNode overview = readJson(mockMvc.perform(get("/api/stats/overview")
                .header(TOKEN_HEADER, "Bearer " + token)).andReturn()).get("data");
        for (String key : Arrays.asList("bookTotal", "categoryTotal", "taskTotal", "todayCollected")) {
            assertTrue(overview.has(key), "指标卡缺少字段：" + key);
            assertTrue(overview.get(key).isNumber(), "指标卡 " + key + " 应为数字");
        }
        assertTrue(overview.get("bookTotal").asLong() > 0L,
                "bookTotal 应大于 0（S2/S3 已采到真实图书）");
        assertEquals(21, overview.get("categoryTotal").asLong(),
                "分类数应为 seed 的 21 个");

        JsonNode charts = readJson(mockMvc.perform(get("/api/stats/charts")
                .header(TOKEN_HEADER, "Bearer " + token)).andReturn()).get("data");
        for (String key : Arrays.asList("categoryDistribution", "ratingDistribution",
                "publishYearTrend", "recentCollected")) {
            assertTrue(charts.has(key), "图表数据缺少字段：" + key);
            assertTrue(charts.get(key).isArray(), key + " 应为数组");
        }

        // 评分分布固定 10 个桶，且必须补齐空桶 —— 否则柱图 X 轴会「缺格」
        JsonNode rating = charts.get("ratingDistribution");
        assertEquals(10, rating.size(), "评分分布应为 10 个桶");
        for (int i = 0; i < 10; i++) {
            assertEquals(i * 100 + "-" + (i * 100 + 100), rating.get(i).get("label").asText());
        }

        // 近 7 天固定 7 个点，且必须补齐没有数据的日子
        JsonNode recent = charts.get("recentCollected");
        assertEquals(7, recent.size(), "近 7 天采集量应为 7 个点");
        for (int i = 0; i < 7; i++) {
            assertTrue(recent.get(i).get("date").asText().matches("\\d{4}-\\d{2}-\\d{2}"),
                    "第 " + i + " 个点的日期格式应为 yyyy-MM-dd");
        }

        // 分类分布：扇区必须带可读名字（字典映射成功），而不是原始 sourceKey
        JsonNode distribution = charts.get("categoryDistribution");
        assertTrue(distribution.size() > 0, "分类分布不应为空（已有采集数据）");
        for (int i = 0; i < distribution.size(); i++) {
            String name = distribution.get(i).get("name").asText();
            assertFalse(name.startsWith("category:") || name.startsWith("ranking:"),
                    "第 " + i + " 个扇区没有映射到字典名：" + name);
            assertTrue(distribution.get(i).get("count").asLong() > 0L);
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 从 handler mapping 里读出真正注册成功的端点，形如 "GET /api/books" */
    private Set<String> registeredEndpoints() {
        Set<String> result = new HashSet<String>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            Set<String> patterns = info.getPatternsCondition() == null
                    ? Collections.<String>emptySet()
                    : info.getPatternsCondition().getPatterns();
            Set<RequestMethod> methods = info.getMethodsCondition() == null
                    ? Collections.<RequestMethod>emptySet()
                    : info.getMethodsCondition().getMethods();

            for (String pattern : patterns) {
                if (methods.isEmpty()) {
                    result.add("ANY " + pattern);
                }
                for (RequestMethod method : methods) {
                    result.add(method.name() + " " + pattern);
                }
            }
        }
        return result;
    }

    private boolean isBusinessPath(String path) {
        return path.startsWith("/api/") && !path.startsWith("/api/ping");
    }

    private int countBusinessEndpoints(Set<String> endpoints) {
        int count = 0;
        for (String endpoint : endpoints) {
            if (isBusinessPath(endpoint.substring(endpoint.indexOf(' ') + 1))) {
                count++;
            }
        }
        return count;
    }

    private int codeOf(MockHttpServletRequestBuilder req) throws Exception {
        return readJson(mockMvc.perform(req).andReturn()).get("code").asInt();
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertNotNull(content, "响应体不应为 null");
        assertFalse(content.isEmpty(), "响应体不应为空");
        return json.readTree(content);
    }

    /** message 必须非空、不含类名/堆栈痕迹，且尽量带上出错的字段名 */
    private void assertReadable(JsonNode body, String expectKeyword) {
        String message = body.get("message").asText();
        assertNotNull(message, "message 不应为 null");
        assertFalse(message.trim().isEmpty(), "message 不应为空");
        assertFalse(message.contains("Exception"), "message 不应暴露异常类名：" + message);
        assertFalse(message.contains("\tat "), "message 不应包含堆栈：" + message);
        if (expectKeyword != null) {
            assertTrue(message.contains(expectKeyword),
                    "message 应指出出错的字段「" + expectKeyword + "」，实际：" + message);
        }
    }

    /**
     * B1/B2 的验证：{@code ex} 字段（Throwable）与 {@code isTrue()/isFalse()}
     * 这两个 getter 都不能出现在 JSON 里。
     *
     * <p>{@code ex} 泄漏会输出整个堆栈；{@code isTrue}/{@code isFalse} 会被
     * Jackson 当布尔属性输出成 {@code "true": true} / {@code "false": false}。
     */
    private void assertNoLeakedFields(JsonNode body, String scene) {
        assertFalse(body.has("ex"), scene + " 泄漏了 ex 字段（Throwable 会被序列化成堆栈）");
        assertFalse(body.has("true"), scene + " 泄漏了 true 字段（isTrue() 被当成布尔属性）");
        assertFalse(body.has("false"), scene + " 泄漏了 false 字段（isFalse() 被当成布尔属性）");
        // 正常字段仍然要在
        assertTrue(body.has("code"), scene + " 缺少 code");
        assertTrue(body.has("message"), scene + " 缺少 message");
        assertTrue(body.has("traceId"), scene + " 缺少 traceId（MDC 透传失效？）");
    }
}
