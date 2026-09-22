package com.bookcollector.api;

import com.bookcollector.book.entity.Book;
import com.bookcollector.task.entity.CollectTask;
import com.bookcollector.task.entity.TaskLog;
import com.bookcollector.taxonomy.entity.TaxonomyItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * S4 的写路径验收 —— 走真实 HTTP 语义（MockMvc）+ 真实 Mongo。
 *
 * <h3>为什么契约测试之外还需要这个类</h3>
 * {@link ApiContractTest} 只证明「读接口能返回 200」。写接口的问题
 * （局部更新把别的字段清空、级联删除漏了子表、唯一索引冲突没转成 400）
 * 全都在读路径上看不出来。S3 就吃过一次亏：{@code TaskControlIT} 漏删日志，
 * 攒出 19 条孤儿记录，界面查不到也永远不会被清理。
 *
 * <h3>数据安全</h3>
 * 所有测试数据都用 {@code s4it-} 前缀，{@link #cleanup()} 按前缀精确删除，
 * <b>绝不碰真实的采集数据</b>。图书那一项用 MongoTemplate 直接插一条合成记录，
 * 而不是拿真书改标题 —— 测试失败在中间时，真数据不会被留在被改状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("S4 · 写路径验收（真实 Mongo）")
class ApiCrudIT {

    private static final String PREFIX = "s4it-";
    private static final String TOKEN_HEADER = "Authorization";

    /** 合成图书的 bookId。这个值永远不会被采集链路写出来 */
    private static final String FAKE_BOOK_ID = PREFIX + "book-1";

    /** 合成目标的 code。用它建字典项和任务，再验证「有任务引用则不许删字典项」 */
    private static final String FAKE_TARGET_ID = PREFIX + "900001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Value("${bookcollector.auth.token}")
    private String token;

    private final ObjectMapper json = new ObjectMapper();

    /** 测试过程中建出来的任务 ID。用于兜底清理日志 —— S3 的孤儿日志就是这么攒出来的 */
    private final List<String> createdTaskIds = new ArrayList<String>();

    @AfterEach
    void cleanup() {
        mongoTemplate.remove(Query.query(Criteria.where("code").regex("^" + PREFIX)),
                TaxonomyItem.class);
        mongoTemplate.remove(Query.query(Criteria.where("targetId").regex("^" + PREFIX)),
                CollectTask.class);
        mongoTemplate.remove(Query.query(Criteria.where("targetId").regex("^" + PREFIX)),
                "collect_cursors");
        mongoTemplate.remove(Query.query(Criteria.where("bookId").regex("^" + PREFIX)),
                Book.class);
        // 日志按 taskId 清。任务被删除接口级联删掉之后，日志就没有别的入口能找到了
        for (String taskId : createdTaskIds) {
            mongoTemplate.remove(Query.query(Criteria.where("taskId").is(taskId)), TaskLog.class);
            mongoTemplate.remove(Query.query(Criteria.where("taskId").is(taskId)),
                    "collect_task_runs");
        }
        createdTaskIds.clear();
    }

    // ==================================================================

    @Test
    @DisplayName("字典：新增 → 编辑 → 被任务引用时拒绝删除 → 删任务后可删")
    void taxonomyLifecycle() throws Exception {
        // --- 新增 ---
        JsonNode created = data(postJson("/api/taxonomy/categories",
                "{\"code\":\"" + FAKE_TARGET_ID + "\",\"name\":\"S4IT 分类\"}"));
        String id = created.get("id").asText();
        assertEquals(FAKE_TARGET_ID, created.get("code").asText());
        assertEquals("CATEGORY", created.get("type").asText());
        assertTrue(created.get("enabled").asBoolean(), "默认应启用");
        assertTrue(created.get("sort").asInt() >= 1, "sort 应被自动分配");

        // --- 编辑 name ---
        JsonNode updated = data(putJson("/api/taxonomy/categories/" + id,
                "{\"name\":\"S4IT 分类-改\"}"));
        assertEquals("S4IT 分类-改", updated.get("name").asText());

        // --- 改 code 必须被拒绝（它是采集目标的身份）---
        JsonNode codeChange = readJson(mockMvc.perform(putJson("/api/taxonomy/categories/" + id,
                "{\"code\":\"s4it-hacked\"}")).andReturn());
        assertEquals(400, codeChange.get("code").asInt(), "改 code 应被拒绝");
        assertTrue(codeChange.get("message").asText().contains("code"),
                "message 应说明是 code 不能改：" + codeChange.get("message").asText());

        // --- 建一个引用该目标的采集任务 ---
        JsonNode task = data(postJson("/api/tasks",
                "{\"targetType\":\"CATEGORY\",\"targetId\":\"" + FAKE_TARGET_ID
                        + "\",\"targetName\":\"S4IT 分类-改\",\"maxPages\":1}"));
        String taskId = task.get("id").asText();
        createdTaskIds.add(taskId);

        // --- 有任务引用 → 拒绝删除字典项 ---
        JsonNode refused = readJson(mockMvc.perform(
                delete("/api/taxonomy/categories/" + id).header(TOKEN_HEADER, bearer())).andReturn());
        assertEquals(400, refused.get("code").asInt(), "有任务引用时删除字典项应被拒绝");
        assertTrue(refused.get("message").asText().contains("任务"),
                "message 应提示先删任务：" + refused.get("message").asText());

        // --- 删掉任务后就能删了 ---
        JsonNode taskDeleted = data(deleteReq("/api/tasks/" + taskId));
        assertEquals(0L, taskDeleted.get("runsDeleted").asLong(), "PENDING 任务没有运行记录");
        assertTrue(taskDeleted.get("logsDeleted").asLong() >= 1L,
                "创建任务时会写一条日志，级联删除应把它带走（S3 的孤儿日志教训）");

        JsonNode deleted = data(deleteReq("/api/taxonomy/categories/" + id));
        assertEquals(1, deleted.get("deleted").asInt());
        assertEquals(0, deleted.get("cursorsRemoved").asInt(), "没有游标，cursorsRemoved 应为 0");
        assertFalse(categoryIds().contains(id), "删掉后列表里不应还有它");
    }

    @Test
    @DisplayName("字典：删除无任务引用的目标时会顺带清理游标")
    void deleteTaxonomyCleansCursor() throws Exception {
        JsonNode created = data(postJson("/api/taxonomy/categories",
                "{\"code\":\"" + FAKE_TARGET_ID + "\",\"name\":\"S4IT 孤儿游标\"}"));
        String id = created.get("id").asText();

        // 先造一条游标（没有任务引用它）
        data(putJson("/api/cursors",
                "{\"targetType\":\"CATEGORY\",\"targetId\":\"" + FAKE_TARGET_ID + "\",\"maxIndex\":77}"));

        JsonNode deleted = data(deleteReq("/api/taxonomy/categories/" + id));
        assertEquals(1, deleted.get("cursorsRemoved").asInt(),
                "★ 删字典项时应顺带清掉它的游标，否则「断点续传」页会留下查不到来源的孤儿记录");

        // 确认游标真的没了
        for (JsonNode item : data(getReq("/api/cursors"))) {
            assertFalse(FAKE_TARGET_ID.equals(item.get("targetId").asText()),
                    "孤儿游标没有被清理：" + item);
        }
    }

    @Test
    @DisplayName("游标：指定起点 → 列表可见 → 重置 → 删除")
    void cursorLifecycle() throws Exception {
        // --- 指定起点 123 ---
        JsonNode set = data(putJson("/api/cursors",
                "{\"targetType\":\"CATEGORY\",\"targetId\":\"" + FAKE_TARGET_ID + "\",\"maxIndex\":123}"));
        assertTrue(set.get("created").asBoolean(), "首次设置应标记 created=true");
        assertEquals(123, set.get("maxIndex").asInt());
        assertFalse(set.get("reset").asBoolean());

        // --- 列表里能查到 ---
        JsonNode found = null;
        for (JsonNode item : data(getReq("/api/cursors"))) {
            if (FAKE_TARGET_ID.equals(item.get("targetId").asText())) {
                found = item;
            }
        }
        assertTrue(found != null, "游标列表里应能查到刚设置的游标");
        assertEquals(123, found.get("maxIndex").asInt());
        String cursorId = found.get("id").asText();

        // --- 重置（maxIndex=0）---
        JsonNode reset = data(putJson("/api/cursors",
                "{\"targetType\":\"CATEGORY\",\"targetId\":\"" + FAKE_TARGET_ID + "\",\"maxIndex\":0}"));
        assertTrue(reset.get("reset").asBoolean(), "maxIndex=0 应被识别为重置");
        assertEquals(0, reset.get("maxIndex").asInt());
        assertFalse(reset.get("created").asBoolean(), "第二次设置 created 应为 false");

        // --- 删除 ---
        JsonNode deleted = data(deleteReq("/api/cursors/" + cursorId));
        assertEquals(1, deleted.get("deleted").asInt());
        assertEquals(404, codeOf(deleteReq("/api/cursors/" + cursorId)), "重复删除应返回 404");
    }

    @Test
    @DisplayName("图书：局部编辑只改传了的字段 → 批量删除返回真实删除数")
    void bookEditAndBatchDelete() throws Exception {
        // 直接插一条合成图书，避免动真实数据
        Book book = Book.builder()
                .bookId(FAKE_BOOK_ID)
                .title("S4IT 原书名")
                .author("S4IT 原作者")
                .intro("S4IT 原简介")
                .publishTime("2020-01-01 00:00:00")
                .firstCollectedAt(new Date())
                .lastCollectedAt(new Date())
                .build();
        mongoTemplate.insert(book);

        // --- 只传 title，author/intro 必须原样保留 ---
        JsonNode edited = data(putJson("/api/books/" + FAKE_BOOK_ID,
                "{\"title\":\"S4IT 新书名\"}"));
        assertEquals("S4IT 新书名", edited.get("title").asText());
        assertEquals("S4IT 原作者", edited.get("author").asText(),
                "★ 局部更新不能把没传的字段清成 null");
        assertEquals("S4IT 原简介", edited.get("intro").asText());
        assertEquals("2020-01-01 00:00:00", edited.get("publishTime").asText());

        // 确认真的落到库里了（不只是返回值好看）
        Book fromDb = mongoTemplate.findOne(
                Query.query(Criteria.where("bookId").is(FAKE_BOOK_ID)), Book.class);
        assertEquals("S4IT 新书名", fromDb.getTitle());

        // --- 空请求体应被拒绝，而不是静默成功 ---
        JsonNode empty = readJson(mockMvc.perform(
                putJson("/api/books/" + FAKE_BOOK_ID, "{}")).andReturn());
        assertEquals(400, empty.get("code").asInt(), "空请求体应返回 400");

        // --- 批量删除：一个存在的 + 一个不存在的 ---
        JsonNode batch = data(postJson("/api/books/batch-delete",
                "{\"bookIds\":[\"" + FAKE_BOOK_ID + "\",\"" + PREFIX + "not-exists\"]}"));
        assertEquals(2, batch.get("requested").asInt());
        assertEquals(1, batch.get("deleted").asInt(), "★ 应返回实际删除数（1）而不是请求数（2）");
        assertEquals(404, codeOf(getReq("/api/books/" + FAKE_BOOK_ID)), "删掉后应查不到");
    }

    @Test
    @DisplayName("任务：新建 → 编辑 → 重复目标被拒 → 终态不能 start → 删除")
    void taskLifecycle() throws Exception {
        JsonNode created = data(postJson("/api/tasks",
                "{\"name\":\"S4IT 任务\",\"targetType\":\"CATEGORY\",\"targetId\":\""
                        + FAKE_TARGET_ID + "\",\"targetName\":\"S4IT 目标\",\"maxPages\":5}"));
        String taskId = created.get("id").asText();
        createdTaskIds.add(taskId);
        assertEquals("PENDING", created.get("status").asText());
        assertEquals(5, created.get("maxPages").asInt());
        assertEquals(0, created.get("runCount").asInt());

        // --- 编辑 ---
        JsonNode edited = data(putJson("/api/tasks/" + taskId,
                "{\"name\":\"S4IT 任务-改\",\"maxPages\":0}"));
        assertEquals("S4IT 任务-改", edited.get("name").asText());
        assertEquals(0, edited.get("maxPages").asInt(), "0 表示不限页数");

        // --- 同一目标再建一个任务 → 被唯一索引拒绝（uk_target）---
        JsonNode dup = readJson(mockMvc.perform(postJson("/api/tasks",
                "{\"targetType\":\"CATEGORY\",\"targetId\":\"" + FAKE_TARGET_ID + "\"}")).andReturn());
        assertEquals(405, dup.get("code").asInt(), "同一目标重复建任务应返回 405（唯一键冲突）");
        assertTrue(dup.get("message").asText().contains("已经有一个任务"),
                "message 应说明目标已占用：" + dup.get("message").asText());

        // --- 取消（PENDING 也能取消，因为没在跑）---
        JsonNode cancel = data(postJson("/api/tasks/" + taskId + "/cancel", null));
        assertEquals(taskId, cancel.get("taskId").asText());
        assertEquals("CANCELED", data(getReq("/api/tasks/" + taskId)).get("status").asText());

        // --- 终态任务不能再 start（应提示用重试）---
        JsonNode startAfterCancel = readJson(mockMvc.perform(
                post("/api/tasks/" + taskId + "/start").header(TOKEN_HEADER, bearer())).andReturn());
        assertEquals(400, startAfterCancel.get("code").asInt(), "已取消的任务不能再 start");
        assertTrue(startAfterCancel.get("message").asText().contains("重试"),
                "message 应引导用户去重试：" + startAfterCancel.get("message").asText());

        // --- 删除 ---
        JsonNode deleted = data(deleteReq("/api/tasks/" + taskId));
        assertEquals(0L, deleted.get("runsDeleted").asLong(),
                "取消一个 PENDING 任务不会建运行记录（运行记录只在真正 launch 时创建）");
        assertTrue(deleted.get("logsDeleted").asLong() >= 2L,
                "创建 + 取消各写一条日志，级联删除应都带走");
        assertEquals(404, codeOf(getReq("/api/tasks/" + taskId)), "删掉后应查不到");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private String bearer() {
        return "Bearer " + token;
    }

    /** 当前字典里所有分类的 id，用于验证「删掉了」 */
    private List<String> categoryIds() throws Exception {
        List<String> ids = new ArrayList<String>();
        for (JsonNode item : data(getReq("/api/taxonomy/categories"))) {
            ids.add(item.get("id").asText());
        }
        return ids;
    }

    private MockHttpServletRequestBuilder getReq(String path) {
        return get(path).header(TOKEN_HEADER, bearer());
    }

    private MockHttpServletRequestBuilder deleteReq(String path) {
        return delete(path).header(TOKEN_HEADER, bearer());
    }

    private MockHttpServletRequestBuilder postJson(String path, String body) {
        MockHttpServletRequestBuilder req = post(path).header(TOKEN_HEADER, bearer())
                .contentType(MediaType.APPLICATION_JSON);
        return body == null ? req : req.content(body);
    }

    private MockHttpServletRequestBuilder putJson(String path, String body) {
        return put(path).header(TOKEN_HEADER, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    /** 执行请求并断言 code=200，返回 data 节点 */
    private JsonNode data(MockHttpServletRequestBuilder req) throws Exception {
        JsonNode body = readJson(mockMvc.perform(req).andReturn());
        assertEquals(200, body.get("code").asInt(),
                "应返回 code=200，实际 code=" + body.get("code").asInt()
                        + " message=" + body.get("message").asText());
        return body.get("data");
    }

    private int codeOf(MockHttpServletRequestBuilder req) throws Exception {
        return readJson(mockMvc.perform(req).andReturn()).get("code").asInt();
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertFalse(content.isEmpty(), "响应体不应为空");
        return json.readTree(content);
    }
}
