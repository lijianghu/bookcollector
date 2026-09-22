package com.bookcollector.common;

/**
 * 业务异常。{@code code} 直接对齐 {@link ResultBean} 的错误码常量。
 *
 * <p>用法：{@code throw new BizException(ResultBean.code_notfound, "图书不存在");}
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public BizException(String message) {
        super(message);
        this.code = ResultBean.code_err;
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    // ---------- 常用快捷构造 ----------

    public static BizException warn(String message) {
        return new BizException(ResultBean.code_warn, message);
    }

    public static BizException notFound(String message) {
        return new BizException(ResultBean.code_notfound, message);
    }

    public static BizException duplicate(String message) {
        return new BizException(ResultBean.code_duplicateKey, message);
    }

    public static BizException sessionInvalid(String message) {
        return new BizException(ResultBean.code_session_invalid, message);
    }
}
