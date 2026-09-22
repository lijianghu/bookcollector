package com.bookcollector.cursor;

import com.bookcollector.common.BizException;
import com.bookcollector.common.ResultBean;
import com.bookcollector.common.enums.TargetType;
import com.bookcollector.cursor.dto.CursorSetRequest;
import com.bookcollector.cursor.entity.CollectCursor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集游标 —— 断点续传（FR-E1 ~ FR-E3）。
 *
 * <h3>⚠️ 重置游标不会删已采到的书</h3>
 * 这是最容易误解的一点，所以在返回值里显式带上 {@code booksKept}。
 * 重置后再跑任务，那些书会被 upsert 更新（幂等），不会重复插入，也不会变少。
 * 想清库得另外调 {@code DELETE /api/books} 之类的接口 —— 本接口不做这件事。
 *
 * <h3>⚠️ 运行中重置游标是「无效」的</h3>
 * 一次运行的起始游标在 {@code launch()} 时就固化进 {@code CollectCommand} 了，
 * 而且采集线程每采完一页就 {@code advance()} 一次 —— 会把你的手工值覆盖掉。
 * 这里不做拦截（因为判断「是否在跑」需要引入 TaskRegistry 依赖，
 * 而它对这个纯字典操作是多余的耦合），改为在响应里说明。
 * 正确姿势：先取消任务，再重置游标，再启动。
 */
@Tag(name = "06. 采集游标", description = "断点续传位置的查看 / 重置 / 指定")
@RestController
@RequestMapping("/api/cursors")
public class CursorController {

    private static final Logger log = LoggerFactory.getLogger(CursorController.class);

    private final CursorStore cursorStore;

    public CursorController(CursorStore cursorStore) {
        this.cursorStore = cursorStore;
    }

    @Operation(summary = "游标列表", description = "按最近更新时间倒序")
    @GetMapping
    public ResultBean<List<CollectCursor>> list() {
        return ResultBean.ok(cursorStore.findAll());
    }

    @Operation(summary = "重置 / 指定游标起点",
            description = "maxIndex=0 或不传 = 重置为 0；>0 = 从该位置继续。不删已采到的图书")
    @PutMapping
    public ResultBean<Map<String, Object>> set(@Validated @RequestBody CursorSetRequest req) {
        TargetType type;
        try {
            type = TargetType.of(req.getTargetType());
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultBean.code_warn, e.getMessage());
        }
        String targetId = req.getTargetId() == null ? "" : req.getTargetId().trim();
        if (targetId.isEmpty()) {
            throw new BizException(ResultBean.code_warn, "targetId 不能为空");
        }

        int maxIndex = req.getMaxIndex() == null ? 0 : req.getMaxIndex();
        if (maxIndex < 0) {
            throw new BizException(ResultBean.code_warn, "maxIndex 不能为负数");
        }

        boolean existed = cursorStore.load(type, targetId) != null;
        cursorStore.setCursor(type, targetId, req.getTargetName(), maxIndex);

        CollectCursor after = cursorStore.load(type, targetId);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("targetType", type.name());
        data.put("targetId", targetId);
        data.put("maxIndex", after == null ? maxIndex : after.getMaxIndex());
        data.put("created", !existed);
        data.put("reset", maxIndex == 0);
        // 说清楚「重置不等于清库」，避免用户以为重置会把书删掉
        data.put("note", "已采到的图书不会被删除；重置后再跑任务会从该位置继续（重复采到的书按 bookId 幂等更新）");
        return ResultBean.ok(data);
    }

    @Operation(summary = "删除游标",
            description = "删除后该目标会被视为「从未采集过」。下次启动任务从 maxIndex=0 开始")
    @DeleteMapping("/{id}")
    public ResultBean<Map<String, Object>> delete(
            @Parameter(description = "游标记录 ID", required = true) @PathVariable String id) {
        CollectCursor cursor = cursorStore.findById(id);
        if (cursor == null) {
            throw BizException.notFound("游标不存在：" + id);
        }
        TargetType type = TargetType.of(cursor.getTargetType());
        cursorStore.delete(type, cursor.getTargetId());

        log.info("已删除游标：{}/{}", cursor.getTargetType(), cursor.getTargetId());
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("id", id);
        data.put("targetType", cursor.getTargetType());
        data.put("targetId", cursor.getTargetId());
        data.put("deleted", 1);
        return ResultBean.ok(data);
    }
}
