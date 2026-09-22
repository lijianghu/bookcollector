package com.bookcollector.common;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.MDC;
import org.springframework.util.Assert;

import java.util.List;

/**
 * 统一返回体。
 *
 * <p>与用户原版的差异，仅 4 处（其余逻辑一字未改）：
 * <ul>
 *   <li><b>B1</b> {@code ex} 字段加 {@code @JsonIgnore} —— 原来只有 {@code @Schema(hidden=true)}，
 *       那只对 Swagger 生效，Jackson 照样会把 Throwable 连同堆栈序列化进响应体。</li>
 *   <li><b>B2</b> {@code isTrue()} / {@code isFalse()} 加 {@code @JsonIgnore} ——
 *       Jackson 按 bean 规范会把它们当布尔属性输出，JSON 里会凭空多出
 *       {@code "true": true} 和 {@code "false": false}。</li>
 *   <li><b>B3</b> fastjson → fastjson2（包名 {@code com.alibaba.fastjson2}）。</li>
 *   <li><b>B4</b> traceId 依赖 {@code TraceIdFilter} 往 MDC 放 "threadId"；
 *       {@code @Async} 线程不继承 MDC，需在任务线程里手动 put/remove。</li>
 * </ul>
 *
 * <p>约定：HTTP 状态码恒为 200，业务结果看 body 里的 {@code code}。
 *
 * @param <T> 业务数据类型
 */
public class ResultBean<T> {

    /** 正常 */
    public static final int code_ok = 200;
    /** 错误 */
    public static final int code_err = 500;
    /** 参数错误 */
    public static final int code_warn = 400;
    /** 未找到 */
    public static final int code_notfound = 404;
    /** 主键重复 */
    public static final int code_duplicateKey = 405;
    /** 身份已失效，请重新登录！ */
    public static final int code_session_invalid = 4100;

    private int code;
    private String message;
    private String traceId;
    private T data;

    @JsonIgnore
    @Schema(hidden = true)
    private Throwable ex;

    public ResultBean() {
    }

    public ResultBean(int code, String msg) {
        this.code = code;
        this.message = msg;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getMessage() {
        String errormsg = message != null ? message : ex != null ? ex.getMessage() : "";
        return errormsg;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setData(T data) {
        this.data = data;
    }

    @JsonIgnore
    public Throwable getEx() {
        return ex;
    }

    public void setEx(Throwable ex) {
        this.ex = ex;
    }

    @SuppressWarnings("rawtypes")
    private static <C extends ResultBean> ResultBean getResultBean(Class<C> beanclass) {
        ResultBean resultBean;
        if (beanclass == null) {
            resultBean = new ResultBean();
        } else {
            try {
                resultBean = beanclass.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                // 原版这里变量名是 ex，会遮蔽同名字段；改成 e 只为可读性
                resultBean = new ResultBean();
            }
        }
        resultBean.setTraceId(MDC.get("threadId"));
        return resultBean;
    }

    public static <T> ResultBean<T> ok(T data) {
        return ok(data, null);
    }

    @SuppressWarnings("unchecked")
    public static <T, C extends ResultBean> ResultBean<T> ok(T data, Class<C> beanclass) {
        ResultBean<T> resultBean = getResultBean(beanclass);
        resultBean.setCode(code_ok);
        resultBean.setData(data);
        return resultBean;
    }

    public static ResultBean warn(Throwable err) {
        return err(code_warn, err);
    }

    public static ResultBean err(Throwable err) {
        return err(code_err, err);
    }

    public static <C extends ResultBean> ResultBean err(Throwable err, Class<C> beanclass) {
        return err(code_err, err, beanclass);
    }

    public static ResultBean err(int errcode, Throwable err) {
        return err(errcode, err, null);
    }

    @SuppressWarnings("unchecked")
    public static <C extends ResultBean> ResultBean err(int errcode, Throwable err, Class<C> beanclass) {
        ResultBean resultBean = getResultBean(beanclass);
        resultBean.setCode(errcode);
        resultBean.setEx(err);
        return resultBean;
    }

    public static ResultBean err(String errmsg) {
        return err(code_err, errmsg, null);
    }

    public static <C extends ResultBean> ResultBean err(String errmsg, Class<C> beanclass) {
        return err(code_err, errmsg, beanclass);
    }

    public static <C extends ResultBean> ResultBean err(int errcode, String errmsg) {
        return err(errcode, errmsg, null);
    }

    @SuppressWarnings("unchecked")
    public static <C extends ResultBean> ResultBean err(int errcode, String errmsg, Class<C> beanclass) {
        ResultBean resultBean = getResultBean(beanclass);
        resultBean.setCode(errcode);
        resultBean.setMessage(errmsg);
        return resultBean;
    }

    @Override
    public String toString() {
        return "code:" + code
                + "\ndata:" + data
                + "\nmessage:" + getMessage();
    }

    /**
     * 检查是否是正确的代码
     */
    public void assert_isTrue() {
        assert_isTrue(getMessage());
    }

    public void assert_isTrue(String message) {
        Assert.isTrue(isTrue(), message);
    }

    @JsonIgnore
    @Schema(hidden = true)
    public boolean isTrue() {
        return this.code == code_ok;
    }

    @JsonIgnore
    @Schema(hidden = true)
    public boolean isFalse() {
        return this.code != code_ok;
    }

    public final <U> U parseObject(Class<U> clazz) {
        return JSONObject.parseObject(JSON.toJSONString(data), clazz);
    }

    /**
     * ⚠️ 注意：fastjson2 里 <b>没有</b> {@code JSONObject.toJSON(Object)}，
     * 对应的 API 是 {@code JSONObject.from(Object)}。
     * 这是 fastjson v1 → v2 除包名之外的真实签名差异之一（已用 javap 核实）。
     */
    public JSONObject parseObject() {
        return JSONObject.from(data);
    }

    /**
     * ⚠️ 注意：fastjson2 里 <b>没有</b> {@code JSONObject.parseArray(String, Class)}，
     * 要用 {@code JSON.parseArray(String, Class)}。
     */
    public <U> List<U> parseArray(Class<U> clazz) {
        return JSON.parseArray(JSON.toJSONString(data), clazz);
    }
}
