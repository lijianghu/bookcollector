package com.bookcollector.taxonomy.controller;

import com.bookcollector.common.ResultBean;
import com.bookcollector.common.enums.TargetType;
import com.bookcollector.taxonomy.entity.TaxonomyItem;
import com.bookcollector.taxonomy.req.TaxonomyRequest;
import com.bookcollector.taxonomy.service.TaxonomyService;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分类与榜单字典（FR-C1 / FR-C2）。
 *
 * <h3>为什么是两套路径而不是一套带 type 参数</h3>
 * {@code /api/taxonomy/categories} 与 {@code /api/taxonomy/rankings}
 * 语义直白，前端菜单和权限（将来）都能按路径切分。代价是 8 个方法里
 * 有 4 对是镜像的 —— 所以下面用 {@link #create}/{@link #update}/{@link #delete}
 * 三个私有方法收口，公开方法只剩一行委托，重复的是路由而不是逻辑。
 */
@Tag(name = "03. 分类与榜单字典", description = "采集目标的增删改查")
@RestController
@RequestMapping("/api/taxonomy")
public class TaxonomyController {

    private final TaxonomyService service;

    public TaxonomyController(TaxonomyService service) {
        this.service = service;
    }

    // ==================================================================
    // 分类
    // ==================================================================

    @Operation(summary = "分类列表",
            description = "enabledOnly=true 时只返回启用的（供「新建任务」下拉框用）")
    @GetMapping("/categories")
    public ResultBean<List<TaxonomyItem>> listCategories(
            @Parameter(description = "是否只返回启用项") @RequestParam(required = false) Boolean enabledOnly) {
        return ResultBean.ok(service.list(TargetType.CATEGORY, enabledOnly));
    }

    @Operation(summary = "新增分类")
    @PostMapping("/categories")
    public ResultBean<TaxonomyItem> createCategory(@Validated @RequestBody TaxonomyRequest req) {
        return ResultBean.ok(service.create(TargetType.CATEGORY, req));
    }

    @Operation(summary = "修改分类", description = "只能改 name / enabled / sort / remark，不能改 code")
    @PutMapping("/categories/{id}")
    public ResultBean<TaxonomyItem> updateCategory(
            @Parameter(description = "字典项 ID", required = true) @PathVariable String id,
            @Validated @RequestBody TaxonomyRequest req) {
        return ResultBean.ok(service.update(id, req));
    }

    @Operation(summary = "删除分类", description = "有采集任务引用它时会拒绝删除")
    @DeleteMapping("/categories/{id}")
    public ResultBean<Map<String, Object>> deleteCategory(
            @Parameter(description = "字典项 ID", required = true) @PathVariable String id) {
        return ResultBean.ok(deleteResult(id));
    }

    // ==================================================================
    // 榜单
    // ==================================================================

    @Operation(summary = "榜单列表")
    @GetMapping("/rankings")
    public ResultBean<List<TaxonomyItem>> listRankings(
            @Parameter(description = "是否只返回启用项") @RequestParam(required = false) Boolean enabledOnly) {
        return ResultBean.ok(service.list(TargetType.RANKING, enabledOnly));
    }

    @Operation(summary = "新增榜单")
    @PostMapping("/rankings")
    public ResultBean<TaxonomyItem> createRanking(@Validated @RequestBody TaxonomyRequest req) {
        return ResultBean.ok(service.create(TargetType.RANKING, req));
    }

    @Operation(summary = "修改榜单", description = "只能改 name / enabled / sort / remark，不能改 code")
    @PutMapping("/rankings/{id}")
    public ResultBean<TaxonomyItem> updateRanking(
            @Parameter(description = "字典项 ID", required = true) @PathVariable String id,
            @Validated @RequestBody TaxonomyRequest req) {
        return ResultBean.ok(service.update(id, req));
    }

    @Operation(summary = "删除榜单", description = "有采集任务引用它时会拒绝删除")
    @DeleteMapping("/rankings/{id}")
    public ResultBean<Map<String, Object>> deleteRanking(
            @Parameter(description = "字典项 ID", required = true) @PathVariable String id) {
        return ResultBean.ok(deleteResult(id));
    }

    // ==================================================================

    /**
     * 删除的统一返回体。
     *
     * <p>把「顺带清理了几个游标」告诉前端，是因为这个副作用不体现在用户操作上 ——
     * 不说明的话，用户下次去「断点续传」页发现少了一行会以为出了 bug。
     */
    private Map<String, Object> deleteResult(String id) {
        int cursorsRemoved = service.delete(id);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("id", id);
        data.put("deleted", 1);
        data.put("cursorsRemoved", cursorsRemoved);
        return data;
    }
}
