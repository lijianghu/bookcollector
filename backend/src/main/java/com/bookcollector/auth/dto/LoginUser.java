package com.bookcollector.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 会话里的登录用户 —— {@code sys_user} 的<b>脱敏投影</b>。
 *
 * <h3>🔴 这个类里绝不能出现 password 字段</h3>
 * 它会被写进 Sa-Token 的会话（存在 Redis 里），也会被塞进
 * {@code /api/auth/me} 的响应体。一旦带了密码摘要，就等于
 * 「登录一次之后，密码摘要长期停留在 Redis 和前端内存里」——
 * 而它存在的唯一理由本来就是「不要到处传密码」。
 * 所以这里刻意<b>不是</b>直接复用 {@link com.bookcollector.auth.entity.SysUser}，
 * 而是重新声明一遍字段：多写 20 行，换掉一个容易踩的坑。
 *
 * <h3>为什么要有 {@code id}</h3>
 * {@code id} 同时是 Sa-Token 的 loginId。{@code ExtractLoginUserHandlerResolver}
 * 会拿它和 {@code AuthStpUtil.getUserId()} 对一遍 ——
 * 这样「会话里的用户」和「token 归属的用户」不一致时能被立刻发现，
 * 而不是让一个身份错乱的对象流进业务代码。
 *
 * <h3>序列化</h3>
 * {@code sa-token-jackson} 的 {@code SaJsonTemplateForJackson} 打开了
 * {@code activateDefaultTyping(DefaultTyping.NON_FINAL)}，所以本类
 * 会带着 {@code @class} 类型信息往返 Redis，反序列化回来仍是
 * {@code LoginUser} 而不是 {@code LinkedHashMap}。
 * 前提是<b>必须有无参构造器</b>（{@code @NoArgsConstructor}）——
 * 删掉它，登录会成功但下一个请求就 4100。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "登录用户（不含密码）")
public class LoginUser implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户主键，同时是 Sa-Token 的 loginId */
    @Schema(description = "用户 ID")
    private String id;

    @Schema(description = "登录名", example = "admin")
    private String username;

    @Schema(description = "展示名", example = "管理员")
    private String nickname;

    /**
     * 角色列表。<b>占位，不参与鉴权</b>。
     *
     * <p>保留它是因为 {@code /api/auth/me} 的响应契约里已经有 {@code roles}
     * （前端菜单按它渲染），删掉会牵动前端。
     */
    @Schema(description = "角色列表（第一期不参与鉴权，仅前端菜单占位）")
    private List<String> roles;
}
