package com.bookcollector.collector;

import com.bookcollector.collector.dto.WereadRawResponse;
import com.bookcollector.common.enums.TargetType;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * S0 阶段的风险闸门：探针。
 *
 * <p>用 RestTemplate 打一次真实的微信读书列表接口，把关键信息打出来，
 * 用来确认「Java 能不能复现 Python 的请求」。
 *
 * <p>只在 {@code probe} profile 下运行：
 * <pre>tools/mvn8.sh -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=probe</pre>
 *
 * <p>它不做任何写库动作，纯读 + 打印。
 */
@Component
@Profile("probe")
public class WereadProbe implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(WereadProbe.class);

    /** 文学，取自原 config.py */
    private static final String DEMO_CATEGORY_ID = "300000";

    private final WereadClient client;

    public WereadProbe(WereadClient client) {
        this.client = client;
    }

    @Override
    public void run(String... args) {
        printHeader();

        WereadRawResponse resp = client.fetchRaw(TargetType.CATEGORY, DEMO_CATEGORY_ID, 0);

        if (!resp.isSuccess()) {
            printFailure(resp);
            return;
        }

        printRawSummary(resp);
        parseAndPrint(resp.getBody());

        printFooter();
    }

    // ------------------------------------------------------------------ 打印

    private void printHeader() {
        log.info("");
        log.info("==================== 微信读书接口探针 ====================");
        log.info("目标：CATEGORY / {}（文学） / maxIndex=0", DEMO_CATEGORY_ID);
        log.info("URL ：{}", client.buildUrl(TargetType.CATEGORY, DEMO_CATEGORY_ID, 0));
    }

    private void printFailure(WereadRawResponse resp) {
        log.error("");
        log.error("✗ 探针失败");
        log.error("  URL      : {}", resp.getUrl());
        log.error("  状态码   : {}", resp.getStatusCode());
        log.error("  耗时     : {}ms", resp.getCostMs());
        log.error("  异常     : {}", resp.getError() == null ? "(无)" : resp.getError().toString());
        log.error("");
        log.error("排查顺序（详见方案文档 5.5）：");
        log.error("  1. 浏览器能不能打开这个 URL？打不开 = 网络/代理问题");
        log.error("  2. 把 URL 换成 https://httpbin.org/headers 再跑一次，对比 Python 与 Java 发出的 Header");
        log.error("  3. 检查 Referer / User-Agent 是否被中间层改掉");
        log.error("  4. 仍不通 → 走代理（Reqable 之类）抓包对比 TLS 指纹");
    }

    private void printRawSummary(WereadRawResponse resp) {
        String body = resp.getBody();
        log.info("");
        log.info("✓ HTTP 通了");
        log.info("  状态码   : {}", resp.getStatusCode());
        log.info("  耗时     : {}ms", resp.getCostMs());
        log.info("  响应长度 : {} 字符", body.length());
        log.info("  响应前 200 字符：{}", body.substring(0, Math.min(200, body.length())));
    }

    private void parseAndPrint(String body) {
        log.info("");
        log.info("---- 解析（fastjson2）----");
        JSONObject root;
        try {
            root = JSON.parseObject(body);
        } catch (Exception e) {
            log.error("✗ JSON 解析失败：{}", e.getMessage());
            log.error("  说明响应不是 JSON —— 可能被 WAF 拦了，返回了 HTML 页面");
            return;
        }

        // 响应包裹：{ books: [...], synckey, hasMore, totalCount }
        log.info("  synckey    : {}", root.getLongValue("synckey"));
        log.info("  hasMore    : {}", root.getBooleanValue("hasMore"));
        log.info("  totalCount : {}", root.getIntValue("totalCount"));

        JSONArray books = root.getJSONArray("books");
        if (books == null || books.isEmpty()) {
            log.error("  ✗ books 为空 —— 接口结构可能变了，或该分类无数据");
            return;
        }
        log.info("  本页条数   : {}", books.size());

        log.info("");
        log.info("---- 前 3 本 ----");
        int limit = Math.min(3, books.size());
        for (int i = 0; i < limit; i++) {
            JSONObject item = books.getJSONObject(i);
            // ⚠️ searchIdx 在外层，不在 bookInfo 里
            Integer searchIdx = item.getInteger("searchIdx");
            Integer type = item.getInteger("type");
            Integer readingCount = item.getInteger("readingCount");
            JSONObject info = item.getJSONObject("bookInfo");
            if (info == null) {
                log.warn("  [{}] bookInfo 为空，跳过", i);
                continue;
            }
            log.info("  [{}] bookId={} | title={} | author={}",
                    i + 1, info.getString("bookId"), info.getString("title"), info.getString("author"));
            log.info("      searchIdx={} type={} readingCount={} | 字段数={}",
                    searchIdx, type, readingCount, info.size());
        }

        // 验证分页游标推进公式：maxIndex = books[-1].searchIdx
        JSONObject last = books.getJSONObject(books.size() - 1);
        log.info("");
        log.info("---- 分页游标验证 ----");
        log.info("  本页最后一条 searchIdx = {} → 下一页 maxIndex 应为这个值",
                last == null ? "(null)" : last.getInteger("searchIdx"));

        // 顺手验证 cleanValue 的边界：接口真的会返回字符串 "null"
        if (hasStringNullValue(books)) {
            log.info("  ⚠️ 检测到字段值是字符串 \"null\" —— cleanValue 必须处理这种情况");
        }
    }

    private boolean hasStringNullValue(JSONArray books) {
        for (int i = 0; i < Math.min(5, books.size()); i++) {
            JSONObject info = books.getJSONObject(i).getJSONObject("bookInfo");
            if (info == null) {
                continue;
            }
            for (String key : info.keySet()) {
                Object v = info.get(key);
                if ("null".equals(v) || "undefined".equals(v) || "".equals(v)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void printFooter() {
        log.info("");
        log.info("========================================================");
        log.info("探针通过。可以进入 S1（数据层）。");
        log.info("");
    }
}
