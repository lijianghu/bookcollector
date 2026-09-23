package com.bookcollector.auth.extract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 在 Controller 方法参数上注入「当前登录用户」。
 *
 * <h3>用法</h3>
 * <pre>{@code
 * @PostMapping("/logout")
 * public ResultBean<Map<String, Object>> logout(
 *         @Parameter(hidden = true) @ExtractLoginUser LoginUser loginUser) {
 *     return ResultBean.ok(service.logout(loginUser));
 * }
 * }</pre>
 *
 * <h3>它做两件事，缺一不可</h3>
 * <ol>
 *   <li><b>校验登录态</b> —— 解析时先 {@code checkLogin()}，未登录抛
 *       {@code NotLoginException} → 4100。所以<b>带了这个注解的参数本身就是一道鉴权</b>，
 *       即使某个接口忘了被拦截器覆盖，它也是安全的</li>
 *   <li><b>注入用户对象</b> —— 免得每个方法都写一遍
 *       {@code AuthStpUtil.getSessionLoginUser()} 再判空</li>
 * </ol>
 * 这两件事必须绑在一起：只注入不校验，等于把「谁在调用」交给调用方猜。
 *
 * <h3>⚠️ 参数类型必须正好是 {@code LoginUser}</h3>
 * {@code ExtractLoginUserHandlerResolver.supportsParameter} 同时检查
 * 「注解存在」+「类型是 {@code LoginUser}」。如果写成别的类型，
 * 解析器不会接管，Spring 会退化成把它当普通参数处理（GET 当查询参数绑定、
 * POST 当请求体），报的错跟鉴权毫无关系，很容易查错方向。
 *
 * <h3>为什么 Swagger 上要加 {@code @Parameter(hidden = true)}</h3>
 * 这个参数不是客户端传的，是框架注入的。不加的话 springdoc 会在
 * Swagger UI 上渲染出一个「必填的 loginUser 输入框」，误导使用者。
 *
 * @see ExtractLoginUserHandlerResolver
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExtractLoginUser {
}
