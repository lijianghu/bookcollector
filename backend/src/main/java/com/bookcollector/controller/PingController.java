package com.bookcollector.controller;

import com.bookcollector.common.ResultBean;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 连通性检查。S0 用来验证「后端起来了 + ResultBean 结构正确 + traceId 有值」。
 */
@Tag(name = "00. 连通性", description = "健康检查")
@RestController
@RequestMapping("/api")
public class PingController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Operation(summary = "ping", description = "返回 pong 与运行信息")
    @GetMapping("/ping")
    public ResultBean<Map<String, Object>> ping() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("message", "pong");
        data.put("app", "bookcollector-admin");
        data.put("time", LocalDateTime.now().format(FMT));
        data.put("javaVersion", System.getProperty("java.version"));
        data.put("jvm", System.getProperty("java.vm.name"));
        data.put("fileEncoding", System.getProperty("file.encoding"));
        data.put("mdThreadId", MDC.get("threadId"));
        data.put("chineseTest", "中文编码测试：微信读书采集器");
        return ResultBean.ok(data);
    }

    @Operation(summary = "故意抛业务异常", description = "验证 GlobalExceptionHandler 与 ResultBean 的错误分支")
    @GetMapping("/ping/biz-error")
    public ResultBean<Void> bizError() {
        throw new com.bookcollector.common.BizException(
                ResultBean.code_warn, "这是一个故意的业务异常（用于验证错误码链路）");
    }
}
