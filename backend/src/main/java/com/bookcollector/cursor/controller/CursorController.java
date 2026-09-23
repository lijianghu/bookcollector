package com.bookcollector.cursor.controller;

import com.bookcollector.common.ResultBean;
import com.bookcollector.cursor.entity.CollectCursor;
import com.bookcollector.cursor.req.CursorSetRequest;
import com.bookcollector.cursor.service.CursorService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 采集游标 —— 断点续传（FR-E1 ~ FR-E3）。
 *
 * <p>这一层是<b>极薄的转发</b>：校验、组装、提示语都在 {@link CursorService} 里。
 *
 * <h3>⚠️ 重置游标不会删已采到的书</h3>
 * 返回值里显式带 {@code note} 说明这件事。想清库得另外调
 * {@code DELETE /api/books} 之类的接口 —— 本接口不做这件事。
 *
 * <h3>⚠️ 运行中重置游标是「无效」的</h3>
 * 采集线程每采完一页就把游标 {@code advance()} 一次，会覆盖手工设置的值。
 * 正确姿势：先取消任务，再重置游标，再启动。
 */
@Tag(name = "06. 采集游标", description = "断点续传位置的查看 / 重置 / 指定")
@RestController
@RequestMapping("/api/cursors")
public class CursorController {

    private final CursorService service;

    public CursorController(CursorService service) {
        this.service = service;
    }

    @Operation(summary = "游标列表", description = "按最近更新时间倒序")
    @GetMapping
    public ResultBean<List<CollectCursor>> list() {
        return ResultBean.ok(service.list());
    }

    @Operation(summary = "重置 / 指定游标起点",
            description = "maxIndex=0 或不传 = 重置为 0；>0 = 从该位置继续。不删已采到的图书")
    @PutMapping
    public ResultBean<Map<String, Object>> set(@Validated @RequestBody CursorSetRequest req) {
        return ResultBean.ok(service.set(req));
    }

    @Operation(summary = "删除游标",
            description = "删除后该目标会被视为「从未采集过」。下次启动任务从 maxIndex=0 开始")
    @DeleteMapping("/{id}")
    public ResultBean<Map<String, Object>> delete(
            @Parameter(description = "游标记录 ID", required = true) @PathVariable String id) {
        return ResultBean.ok(service.delete(id));
    }
}
