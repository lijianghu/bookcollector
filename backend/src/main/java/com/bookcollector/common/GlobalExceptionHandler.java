package com.bookcollector.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 全局异常处理。
 *
 * <p>约定：HTTP 状态码恒为 200，业务结果看 body 里的 {@code code}。
 * 这样前端只需要在 axios 拦截器里判断一处 {@code code}，不用同时处理 HTTP 状态码。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 参数校验失败时最多列几条。超出只报总数，避免 message 变成一堵墙 */
    private static final int MAX_REPORTED_ERRORS = 3;

    /** 业务异常：code 由抛出方指定 */
    @ExceptionHandler(BizException.class)
    public ResultBean<Void> handleBiz(BizException e, HttpServletRequest request) {
        log.warn("[业务异常] {} {} -> code={}, msg={}",
                request.getMethod(), request.getRequestURI(), e.getCode(), e.getMessage());
        return ResultBean.err(e.getCode(), e.getMessage());
    }

    /** MongoDB 唯一索引冲突 → 405 */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResultBean<Void> handleDuplicateKey(DuplicateKeyException e, HttpServletRequest request) {
        log.warn("[唯一键冲突] {} {}", request.getMethod(), request.getRequestURI());
        return ResultBean.err(ResultBean.code_duplicateKey, "数据已存在（唯一键冲突）");
    }

    /** @Valid 校验失败（@RequestBody） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResultBean<Void> handleValidation(MethodArgumentNotValidException e) {
        return ResultBean.err(ResultBean.code_warn, describeFieldErrors(e.getBindingResult().getFieldErrors()));
    }

    /** @Valid 校验失败（表单绑定） */
    @ExceptionHandler(BindException.class)
    public ResultBean<Void> handleBind(BindException e) {
        return ResultBean.err(ResultBean.code_warn, describeFieldErrors(e.getBindingResult().getFieldErrors()));
    }

    /** 缺少必填请求参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResultBean<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return ResultBean.err(ResultBean.code_warn, "缺少必填参数：" + e.getParameterName());
    }

    /** 请求体不是合法 JSON */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResultBean<Void> handleNotReadable(HttpMessageNotReadableException e) {
        return ResultBean.err(ResultBean.code_warn, "请求体格式错误，无法解析");
    }

    /** 兜底：任何未捕获异常 */
    @ExceptionHandler(Exception.class)
    public ResultBean<Void> handleAny(Exception e, HttpServletRequest request) {
        log.error("[未捕获异常] {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResultBean.err(ResultBean.code_err, e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    /**
     * 把字段错误拼成一句给人看的 message。
     *
     * <h3>🔴 为什么要先排序</h3>
     * Hibernate Validator <b>不保证</b> {@code getFieldErrors()} 的顺序
     * （它内部用 {@code HashSet} 收集约束违反）。这意味着同一个请求刷新两次，
     * 用户可能看到「username 不能为空」和「password 不能为空」交替出现 ——
     * 看起来像系统在乱说话。按字段名排序后，同样的输入永远得到同样的提示。
     * 这个问题是在跑全量回归时才暴露出来的（单独跑这个测试类时恰好顺序一致）。
     *
     * <h3>为什么报多个而不是只报第一个</h3>
     * 提交一个空表单时，用户想知道的是「还差哪些」，而不是「差一个，改完再来一次」。
     * 但也不该无节制地全列出来 —— 上限 {@value #MAX_REPORTED_ERRORS} 条，
     * 超出部分只说个数。
     */
    private String describeFieldErrors(List<FieldError> errors) {
        if (errors == null || errors.isEmpty()) {
            return "参数校验失败";
        }

        List<FieldError> sorted = new ArrayList<FieldError>(errors);
        Collections.sort(sorted, new Comparator<FieldError>() {
            @Override
            public int compare(FieldError a, FieldError b) {
                String fa = a.getField() == null ? "" : a.getField();
                String fb = b.getField() == null ? "" : b.getField();
                return fa.compareTo(fb);
            }
        });

        StringBuilder sb = new StringBuilder();
        int limit = Math.min(sorted.size(), MAX_REPORTED_ERRORS);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append("；");
            }
            sb.append(describeOne(sorted.get(i)));
        }
        if (sorted.size() > limit) {
            sb.append("（共 ").append(sorted.size()).append(" 项参数有问题）");
        }
        return sb.toString();
    }

    private String describeOne(FieldError error) {
        // ⚠️ 类型转换失败必须单独处理。
        //
        // Spring 的默认消息长这样：
        //   minRating Failed to convert property value of type 'java.lang.String'
        //   to required type 'java.lang.Integer' for property 'minRating';
        //   nested exception is java.lang.NumberFormatException: For input string: "abc"
        //
        // 这句话对「写 Java 的人」是清楚的，对「点界面的人」完全是噪音，
        // 而且它把内部类型和嵌套异常类名都暴露了出去。验收标准要求
        // 「message 可读」，所以这里换成人话：哪个参数、什么值、哪里不对。
        if (error.isBindingFailure()) {
            return "参数「" + error.getField() + "」格式不正确（收到：" + error.getRejectedValue() + "）";
        }
        // 校验注解失败（@NotBlank / @Min …）：默认消息是注解上写的，本来就该给人看
        return error.getField() + " " + error.getDefaultMessage();
    }
}
