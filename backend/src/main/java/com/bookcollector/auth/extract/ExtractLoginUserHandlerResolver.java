package com.bookcollector.auth.extract;

import com.bookcollector.auth.dto.LoginUser;
import com.bookcollector.auth.stp.AuthStpUtil;
import com.bookcollector.common.BizException;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@link ExtractLoginUser} 的参数解析器 —— 把「当前登录用户」注入 Controller 方法。
 *
 * <h3>为什么用 {@code HandlerMethodArgumentResolver} 而不是在 Controller 里取</h3>
 * 每个需要「谁在调用」的接口都写一遍
 * {@code AuthStpUtil.checkLogin(); LoginUser u = AuthStpUtil.getSessionLoginUser();}
 * 有三个问题：重复、容易漏掉校验、以及<b>取到的对象没人验身份一致性</b>。
 * 收敛到解析器里，上面三件事只写一次。
 *
 * <h3>🔴 身份一致性校验为什么不能省</h3>
 * {@code AuthStpUtil.getUserId()} 是「token 归属于谁」，会话里的
 * {@code LoginUser.id} 是「会话里记的是谁」。正常情况下两者必然相等 ——
 * 但「正常情况」不该是代码的前提假设。如果哪天出现会话被错误复用
 * （比如有人把 loginType 改回默认值导致跨应用串号），
 * 一个不校验的解析器会把<b>别人的身份</b>安静地交给业务代码，
 * 而校验版本会立刻返回 4100。这是几行代码换掉一整类严重问题。
 *
 * <h3>异常选型：为什么用 {@code BizException.sessionInvalid}</h3>
 * 它是本项目既有的「业务异常 → 统一返回体」通道，{@code code} 直接就是
 * {@code 4100}（{@code ResultBean.code_session_invalid}），
 * {@code GlobalExceptionHandler} 已有分支处理。
 * 为它单独造一个 {@code AuthInvalidException} 只会多一个类型、多一条
 * 异常分支，契约却完全一样 —— 不划算。
 *
 * <h3>⚠️ 解析器抛出的异常会被全局处理器接住</h3>
 * 参数解析发生在 {@code DispatcherServlet} 的 {@code ha.handle(...)} 内部，
 * 而它被 {@code try} 包着，异常会走到 {@code processDispatchResult} →
 * {@code ExceptionHandlerExceptionResolver}。所以这里抛异常是「正常返回
 * 4100 统一返回体」，不需要自己写响应（与拦截器不同，见
 * {@code auth/AGENTS.md} 铁律 3）。
 */
@Component
public class ExtractLoginUserHandlerResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == LoginUser.class
                && parameter.hasParameterAnnotation(ExtractLoginUser.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        // ① 先校验登录态。未登录 → NotLoginException → 4100
        AuthStpUtil.checkLogin();

        // ② 取会话里的用户
        LoginUser loginUser = AuthStpUtil.getSessionLoginUser();

        // ③ 身份一致性校验：会话里没有用户、或用户与 token 归属不一致，一律当作登录失效
        if (loginUser == null || !AuthStpUtil.getUserId().equals(loginUser.getId())) {
            throw BizException.sessionInvalid("登录信息已失效，请重新登录");
        }
        return loginUser;
    }
}
