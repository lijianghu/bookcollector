package com.bookcollector.task.controller;

import com.bookcollector.common.PageResult;
import com.bookcollector.common.ResultBean;
import com.bookcollector.task.entity.TaskLog;
import com.bookcollector.task.entity.TaskRun;
import com.bookcollector.task.service.TaskService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运行记录（FR-D5）。
 *
 * <h3>为什么运行记录单独一个 Controller，而不是挂在 {@code /api/tasks/{id}/runs} 下</h3>
 * 因为「运行记录」有<b>两个</b>自然的入口：从任务进（「这个任务的历次运行」）
 * 和从全局进（「最近跑了些什么」）。挂在任务下就只支持前者。
 * 用 {@code /api/runs?taskId=x} 一个路径同时覆盖两者 —— 不传 {@code taskId}
 * 就是全局视图。
 *
 * <p>详情里的 {@code lastProgressAt} 是判断「任务是否假死」的唯一可靠依据
 * （见 {@code TaskRun} 类注释）：页面轮询时如果
 * {@code now - lastProgressAt} 远超一页的正常耗时，说明卡住了，
 * 而不是「在慢慢跑」。
 */
@Tag(name = "05. 运行记录", description = "运行流水与日志")
@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final TaskService service;

    public RunController(TaskService service) {
        this.service = service;
    }

    @Operation(summary = "运行记录列表",
            description = "分页。taskId / status 都可选，都不传就是全部运行记录（按开始时间倒序）")
    @GetMapping
    public ResultBean<PageResult<TaskRun>> page(
            @Parameter(description = "按任务筛选") @RequestParam(required = false) String taskId,
            @Parameter(description = "按状态筛选：RUNNING / PAUSED / SUCCESS / FAILED / CANCELED / INTERRUPTED")
            @RequestParam(required = false) String status,
            @Parameter(description = "页码，从 1 开始") @RequestParam(required = false) Integer page,
            @Parameter(description = "每页条数，默认 20，上限 200") @RequestParam(required = false) Integer size) {
        return ResultBean.ok(service.pageRuns(taskId, status, page, size));
    }

    @Operation(summary = "运行详情", description = "含实时进度快照（pagesDone / booksSaved / currentCursor / lastProgressAt）")
    @GetMapping("/{id}")
    public ResultBean<TaskRun> detail(
            @Parameter(description = "运行 ID", required = true) @PathVariable String id) {
        return ResultBean.ok(service.getRun(id));
    }

    @Operation(summary = "运行日志", description = "按落库时间正序（先发生先显示），默认最多 500 条")
    @GetMapping("/{id}/logs")
    public ResultBean<List<TaskLog>> logs(
            @Parameter(description = "运行 ID", required = true) @PathVariable String id,
            @Parameter(description = "最多返回条数，默认取配置的 log-list-limit")
            @RequestParam(required = false) Integer limit) {
        // 先确认 run 存在：否则「runId 写错了」会表现成一个空数组，
        // 前端无法区分「这次运行确实没日志」和「你查的 run 根本不存在」
        service.getRun(id);
        return ResultBean.ok(service.listLogs(id, limit));
    }
}
