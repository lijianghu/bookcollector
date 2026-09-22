package com.bookcollector.collector;

import com.bookcollector.audit.ApiRequestRepository;
import com.bookcollector.book.entity.Book;
import com.bookcollector.common.enums.TargetType;
import com.bookcollector.config.WereadProperties;
import com.bookcollector.cursor.CursorStore;
import com.bookcollector.cursor.entity.CollectCursor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分页采集主循环 —— S2 的技术核心。
 *
 * <h3>一页的完整流程</h3>
 * <pre>
 * checkPoint()                      ← 暂停则阻塞、取消则跳出
 *   ↓
 * fetchWithRetry()                  ← 失败重试 3 次，退避 1s/3s/9s；4xx 不重试
 *   ↓
 * WereadPage.parse()                ← 解析响应信封
 *   ↓
 * audit.recordPage()                ← 落 api_requests（无论成败）
 *   ↓
 * BookParser.parseAll()             ← 单本失败只跳过，不搞挂整页
 *   ↓
 * BookWriter.upsertAll()            ← ★ 先落库
 *   ↓
 * CursorStore.advance()             ← ★ 再推进游标（顺序不能反，见下）
 *   ↓
 * ProgressReporter.onPage()         ← 刷新进度（每页一次，不是每本）
 * </pre>
 *
 * <h3>🔴 为什么必须「先落库、再推进游标」</h3>
 * 反过来的话，一旦在两者之间崩溃，游标已经跳过这批书 —— 它们<b>永久丢失</b>，
 * 下次续传会从更后面开始，谁也不知道中间少了什么。
 * 按正确顺序最坏只是「重复采同一页」，而 upsert 是幂等的，重复采没有副作用。
 * （风险 R5）
 *
 * <h3>三种终止方式</h3>
 * <ol>
 *   <li><b>正常采到底</b>：{@code hasMore=false}，或本页返回空列表</li>
 *   <li><b>达到页数上限</b>：{@code maxPages}，游标保留，下次可续传</li>
 *   <li><b>失败/取消</b>：{@code errorMsg} 或 {@code canceled} 非空，游标同样保留</li>
 * </ol>
 *
 * <p>本类<b>不碰任务状态</b> —— 它只负责采，采完把结果交给上层（S3 的 TaskRunner），
 * 由上层决定把 run 标成 SUCCESS / FAILED / CANCELED。这样 S2 可以独立测试。
 */
@Component
public class CollectLoop {

    private static final Logger log = LoggerFactory.getLogger(CollectLoop.class);

    /** 重试退避的起始值（毫秒），每次 ×3 → 1s / 3s / 9s */
    private static final long BACKOFF_BASE_MS = 1000L;

    /** 退避倍数 */
    private static final long BACKOFF_FACTOR = 3L;

    private final WereadClient client;
    private final BookParser parser;
    private final BookWriter writer;
    private final CursorStore cursorStore;
    private final ProgressReporter progress;
    private final ApiRequestRepository audit;
    private final WereadProperties props;
    private final SimpleRateLimiter rateLimiter;
    private final boolean auditEnabled;

    public CollectLoop(WereadClient client,
                       BookParser parser,
                       BookWriter writer,
                       CursorStore cursorStore,
                       ProgressReporter progress,
                       ApiRequestRepository audit,
                       WereadProperties props) {
        this.client = client;
        this.parser = parser;
        this.writer = writer;
        this.cursorStore = cursorStore;
        this.progress = progress;
        this.audit = audit;
        this.props = props;
        // 限速器按配置构造（SimpleRateLimiter 不是 Spring bean，因为它是有状态的）
        this.rateLimiter = new SimpleRateLimiter(props.getRateLimitPerSecond());
        this.auditEnabled = props.isAuditEnabled();
    }

    /**
     * 跑一次采集。
     *
     * <p><b>不抛异常</b> —— 所有失败都装进 {@link CollectResult} 返回。
     * 采集是长任务，异常穿透到线程池会被吞掉，不如显式返回结果让上层处理。
     */
    public CollectResult run(CollectCommand cmd) {
        cmd.validate();

        long startedAt = System.currentTimeMillis();
        TargetType type = cmd.getTargetType();
        String targetId = cmd.getTargetId();
        String sourceKey = cmd.sourceKey();
        CollectControl control = cmd.controlOrDefault();
        int maxPages = cmd.effectiveMaxPages();

        // ---- 起始游标 ----
        if (cmd.isResetCursor()) {
            cursorStore.reset(type, targetId);
        }
        CollectCursor cursor = cursorStore.load(type, targetId);
        int maxIndex = (cursor == null || cursor.getMaxIndex() == null) ? 0 : cursor.getMaxIndex();
        // totalCollected 是「该目标累计处理过的条数」，跨运行累加，
        // 所以这里先取历史基数，每页写回「基数 + 本次累计」。
        // （reset 已把基数归零，见 CursorStore.reset）
        long baseCollected = (cursor == null || cursor.getTotalCollected() == null)
                ? 0L : cursor.getTotalCollected();
        int currentCursor = maxIndex;
        if (maxIndex > 0) {
            log.info("从游标 {} 续传：{} / {}", maxIndex, type, targetId);
            progress.info(cmd.getRunId(), cmd.getTaskId(),
                    "从游标 " + maxIndex + " 续传（断点续传）");
        }

        int page = 0;
        int totalParsed = 0;
        int totalSaved = 0;
        Integer totalCount = null;
        boolean hasMore = true;
        boolean canceled = false;
        String errorMsg = null;

        try {
            while (true) {
                control.checkPoint();
                int pageNo = page + 1;

                // ---- 1. 发请求（含重试）----
                WereadRawResponse resp = fetchWithRetry(cmd, maxIndex);

                // ---- 2. 解析信封 ----
                WereadPage pageData = null;
                String failReason = null;
                if (resp.isSuccess()) {
                    try {
                        pageData = WereadPage.parse(resp.getBody());
                    } catch (IllegalArgumentException e) {
                        failReason = "响应解析失败：" + e.getMessage();
                    }
                } else {
                    failReason = describeFailure(resp);
                }

                // ---- 3. 审计（成功失败都记）----
                if (auditEnabled) {
                    audit.recordPage(cmd.getRunId(), type, targetId, pageNo, maxIndex,
                            resp, pageData, pageData == null ? 0 : pageData.size());
                }

                // ---- 4. 失败则终止 ----
                if (pageData == null) {
                    errorMsg = "第 " + pageNo + " 页采集失败：" + failReason;
                    log.error("{}（{} / {} maxIndex={}）", errorMsg, type, targetId, maxIndex);
                    progress.error(cmd.getRunId(), cmd.getTaskId(), errorMsg);
                    break;
                }

                // ---- 5. 空页 = 采到底 ----
                if (pageData.isEmpty()) {
                    // 不能因为「本页为空」就认为还有数据 —— hasMore 可能仍为 true，
                    // 但继续翻页只会拿到同样的空页，所以这里直接结束，避免死循环。
                    hasMore = false;
                    log.info("第 {} 页返回空列表，采集结束（{} / {}）", pageNo, type, targetId);
                    progress.warn(cmd.getRunId(), cmd.getTaskId(),
                            "第 " + pageNo + " 页返回空列表，采集结束");
                    break;
                }

                // ---- 6. 解析 + 落库 ----
                List<Book> books = parser.parseAll(pageData.getBooks());
                int saved = writer.upsertAll(books, sourceKey);

                page++;
                totalParsed += books.size();
                totalSaved += saved;
                totalCount = pageData.getTotalCount();
                hasMore = pageData.isHasMore();

                // ---- 7. ★ 先落库（上面已做），再推进游标 ----
                Integer nextCursor = pageData.lastSearchIdx();
                int newCursor = nextCursor == null ? maxIndex : nextCursor;
                cursorStore.advance(type, targetId, cmd.getTargetName(), newCursor,
                        baseCollected + totalSaved, totalCount, !hasMore, cmd.getRunId());
                currentCursor = newCursor;

                // ---- 8. 进度 ----
                progress.onPage(cmd.getRunId(), page, totalSaved, newCursor, totalCount);
                String line = String.format(
                        "第 %d 页：解析 %d 本、写入 %d 本，游标 %d → %d，hasMore=%s",
                        page, books.size(), saved, maxIndex, newCursor, hasMore);
                log.info("{}（{} / {}）", line, type, targetId);
                progress.info(cmd.getRunId(), cmd.getTaskId(), line);

                // ---- 9. 终止判断 ----
                if (!hasMore) {
                    break;
                }
                if (maxPages > 0 && page >= maxPages) {
                    log.info("已达到页数上限 {}，停止（{} / {}）。游标 {} 保留，可续传",
                            maxPages, type, targetId, newCursor);
                    progress.info(cmd.getRunId(), cmd.getTaskId(),
                            "已达到页数上限 " + maxPages + "，游标 " + newCursor + " 保留，可续传");
                    break;
                }
                if (nextCursor == null) {
                    // 取不到游标就不能推进，否则 maxIndex 不变 → 死循环
                    log.warn("本页最后一条缺 searchIdx，无法推进游标，提前结束（{} / {}）",
                            type, targetId);
                    progress.warn(cmd.getRunId(), cmd.getTaskId(),
                            "本页最后一条缺 searchIdx，无法推进游标，提前结束");
                    break;
                }

                maxIndex = newCursor;
                // ---- 10. 限速 ----
                rateLimiter.acquire();
            }
        } catch (CollectCanceledException e) {
            canceled = true;
            log.info("采集被取消：{} / {}，已采 {} 页", type, targetId, page);
            progress.warn(cmd.getRunId(), cmd.getTaskId(),
                    "采集被取消，已采 " + page + " 页（游标 " + currentCursor + " 已保留，可恢复）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errorMsg = "采集线程被中断";
            log.warn("{}（{} / {}，已采 {} 页）", errorMsg, type, targetId, page);
            progress.error(cmd.getRunId(), cmd.getTaskId(),
                    errorMsg + "，已采 " + page + " 页（游标保留）");
        } catch (Exception e) {
            errorMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("采集异常（{} / {}，已采 {} 页）", type, targetId, page, e);
            progress.error(cmd.getRunId(), cmd.getTaskId(),
                    "采集异常：" + errorMsg + "，已采 " + page + " 页（游标保留）");
        }

        long cost = System.currentTimeMillis() - startedAt;
        CollectResult result = CollectResult.builder()
                .pagesDone(page)
                .booksParsed(totalParsed)
                .booksSaved(totalSaved)
                .currentCursor(currentCursor)
                .hasMore(hasMore)
                .totalCount(totalCount)
                .canceled(canceled)
                .errorMsg(errorMsg)
                .costMs(cost)
                .build();

        log.info("采集结束：{} 页 / 解析 {} 本 / 写入 {} 本 / 游标 {} / 耗时 {}ms / {}",
                result.getPagesDone(), result.getBooksParsed(), result.getBooksSaved(),
                result.getCurrentCursor(), result.getCostMs(),
                result.isSuccess() ? "成功" : (result.isCanceled() ? "已取消" : "失败：" + errorMsg));
        return result;
    }

    /**
     * 带重试的取页。
     *
     * <p>策略（对齐 4.7.4）：
     * <ul>
     *   <li>网络异常 / 5xx / 429 → 重试，指数退避 1s → 3s → 9s</li>
     *   <li>4xx → <b>不重试</b>，重试没有意义，直接返回让上层记失败</li>
     * </ul>
     * <p>重试次数用配置里的 {@code bookcollector.weread.max-retry}（默认 3）。
     */
    private WereadRawResponse fetchWithRetry(CollectCommand cmd, int maxIndex)
            throws InterruptedException {
        int maxAttempts = Math.max(1, props.getMaxRetry());
        long backoff = BACKOFF_BASE_MS;
        WereadRawResponse last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            WereadRawResponse resp = client.fetchRaw(cmd.getTargetType(), cmd.getTargetId(), maxIndex);
            if (resp.isSuccess()) {
                return resp;
            }
            last = resp;

            if (!resp.isRetryable()) {
                log.warn("状态码 {} 不可重试，放弃（{} / {} maxIndex={}）：{}",
                        resp.getStatusCode(), cmd.getTargetType(), cmd.getTargetId(), maxIndex,
                        describeFailure(resp));
                return resp;
            }
            if (attempt < maxAttempts) {
                log.warn("第 {}/{} 次请求失败，{} ms 后重试（{} / {} maxIndex={}）：{}",
                        attempt, maxAttempts, backoff,
                        cmd.getTargetType(), cmd.getTargetId(), maxIndex, describeFailure(resp));
                Thread.sleep(backoff);
                backoff *= BACKOFF_FACTOR;
            }
        }
        return last;
    }

    /**
     * 把一次失败翻译成一句人话，写进日志和审计的 errorMsg。
     *
     * <p>真正的措辞在 {@link WereadRawResponse#describeFailure()} —— 审计落库那边也调它，
     * 保证「任务失败原因」和「请求审计里的原因」是同一句话。这里只补一个 null 保护。
     */
    private String describeFailure(WereadRawResponse resp) {
        return resp == null ? "无响应" : resp.describeFailure();
    }
}
