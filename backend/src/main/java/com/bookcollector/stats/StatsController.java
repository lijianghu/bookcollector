package com.bookcollector.stats;

import com.bookcollector.common.ResultBean;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 仪表盘（FR-F1 / FR-F2）。
 *
 * <p>刻意拆成两个端点而不是一个：指标卡要的是「立刻出数字」，
 * 4 张图要跑 4 条聚合管道。合成一个端点的话，图表那边一旦慢，
 * 顶部的数字也跟着一起等 —— 而数字恰恰是最该先显示出来的东西。
 */
@Tag(name = "07. 仪表盘", description = "指标卡与图表数据")
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService service;

    public StatsController(StatsService service) {
        this.service = service;
    }

    @Operation(summary = "指标卡",
            description = "bookTotal 图书总数 / categoryTotal 分类数 / taskTotal 任务数 / "
                    + "todayCollected 今日新增图书数。另有 taskRunning、activeRuns、"
                    + "lastCollectedAt 等上下文信息")
    @GetMapping("/overview")
    public ResultBean<Map<String, Object>> overview() {
        return ResultBean.ok(service.overview());
    }

    @Operation(summary = "图表数据",
            description = "categoryDistribution 分类分布（饼，按采集目标） / "
                    + "ratingDistribution 评分分布（柱，10 个桶） / "
                    + "publishYearTrend 出版年份趋势（折线） / "
                    + "recentCollected 近 7 天采集量（柱，已补齐无数据的日期）")
    @GetMapping("/charts")
    public ResultBean<Map<String, Object>> charts() {
        return ResultBean.ok(service.charts());
    }
}
