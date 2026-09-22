package com.bookcollector.task;

import com.bookcollector.common.ResultBean;
import com.bookcollector.task.dto.TaskCreateRequest;
import com.bookcollector.task.dto.TaskUpdateRequest;
import com.bookcollector.task.entity.CollectTask;
import com.bookcollector.task.entity.TaskRun;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 采集任务（FR-D1 ~ FR-D4 / FR-D7）。
 *
 * <p>这一层是<b>极薄的转发</b>：状态机的全部规则都在 {@link TaskService} 里，
 * 因为那些规则必须和 {@link TaskRunner} 的写终态逻辑配套看才能理解，
 * 拆到 Controller 会让人以为「状态判断是接口层的事」。
 *
 * <h3>为什么 {@code start} 和 {@code retry} 是两个端点</h3>
 * 它们的准入状态集合<b>不重叠</b>：{@code start} 只接受 PENDING / INTERRUPTED，
 * {@code retry} 只接受终态（SUCCESS / FAILED / CANCELED）。合并成一个
 * 「开始跑」端点的话，那个端点就得对任意状态都放行 —— 于是「任务已完成，
 * 再点启动」会静默地重新跑一遍，而不是提示「请用重试」。
 * 两个端点的报错信息才能各说各的话。
 */
@Tag(name = "04. 采集任务", description = "任务增删改查 + 启动/暂停/恢复/取消/重试")
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService service;

    public TaskController(TaskService service) {
        this.service = service;
    }

    // ==================================================================
    // 增删改查
    // ==================================================================

    @Operation(summary = "任务列表", description = "按创建时间倒序")
    @GetMapping
    public ResultBean<List<CollectTask>> list() {
        return ResultBean.ok(service.list());
    }

    @Operation(summary = "新建任务",
            description = "初始状态 PENDING。注意：一个目标只能有一个任务（uk_target 唯一索引）")
    @PostMapping
    public ResultBean<CollectTask> create(@Validated @RequestBody TaskCreateRequest req) {
        return ResultBean.ok(service.create(req.getName(), req.getTargetType(), req.getTargetId(),
                req.getTargetName(), req.getMaxPages(), req.getRemark()));
    }

    @Operation(summary = "任务详情")
    @GetMapping("/{id}")
    public ResultBean<CollectTask> detail(
            @Parameter(description = "任务 ID", required = true) @PathVariable String id) {
        return ResultBean.ok(service.get(id));
    }

    @Operation(summary = "编辑任务",
            description = "只能改 name / maxPages / remark。目标不可改；运行中不允许编辑")
    @PutMapping("/{id}")
    public ResultBean<CollectTask> update(
            @Parameter(description = "任务 ID", required = true) @PathVariable String id,
            @Validated @RequestBody TaskUpdateRequest req) {
        return ResultBean.ok(service.update(id, req.getName(), req.getMaxPages(), req.getRemark()));
    }

    @Operation(summary = "删除任务",
            description = "级联删除该任务的全部运行记录与日志；运行中的任务必须先取消。不删游标")
    @DeleteMapping("/{id}")
    public ResultBean<Map<String, Object>> delete(
            @Parameter(description = "任务 ID", required = true) @PathVariable String id) {
        return ResultBean.ok(service.delete(id));
    }

    // ==================================================================
    // 四个动作 + 重试
    // ==================================================================

    @Operation(summary = "启动", description = "PENDING / INTERRUPTED → RUNNING，从游标续传")
    @PostMapping("/{id}/start")
    public ResultBean<TaskRun> start(
            @Parameter(description = "任务 ID", required = true) @PathVariable String id) {
        return ResultBean.ok(service.start(id));
    }

    @Operation(summary = "暂停",
            description = "RUNNING → PAUSED。请求式暂停：当前页跑完后停住，游标保留")
    @PostMapping("/{id}/pause")
    public ResultBean<Map<String, Object>> pause(
            @Parameter(description = "任务 ID", required = true) @PathVariable String id) {
        service.pause(id);
        return ResultBean.ok(Collections.<String, Object>singletonMap("taskId", id));
    }

    @Operation(summary = "恢复",
            description = "PAUSED → 原地唤醒（同一个 run，不重采）；INTERRUPTED → 新开一次运行")
    @PostMapping("/{id}/resume")
    public ResultBean<TaskRun> resume(
            @Parameter(description = "任务 ID", required = true) @PathVariable String id) {
        return ResultBean.ok(service.resume(id));
    }

    @Operation(summary = "取消", description = "非终态 → CANCELED，并从游标处保留断点")
    @PostMapping("/{id}/cancel")
    public ResultBean<Map<String, Object>> cancel(
            @Parameter(description = "任务 ID", required = true) @PathVariable String id) {
        service.cancel(id);
        return ResultBean.ok(Collections.<String, Object>singletonMap("taskId", id));
    }

    @Operation(summary = "重试", description = "终态（SUCCESS / FAILED / CANCELED）→ 再跑一次，从当前游标续传")
    @PostMapping("/{id}/retry")
    public ResultBean<TaskRun> retry(
            @Parameter(description = "任务 ID", required = true) @PathVariable String id) {
        return ResultBean.ok(service.retry(id));
    }
}
