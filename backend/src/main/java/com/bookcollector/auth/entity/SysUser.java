package com.bookcollector.auth.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 登录用户（{@code sys_user}）。
 *
 * <h3>为什么第一期就有这张表</h3>
 * 需求 FR-A1 ~ FR-A4 原本写的是「账号密码写死在 {@code application.yml}」，
 * 但那样有两个绕不过去的问题：
 * <ol>
 *   <li><b>改密码要重启</b>。{@code AuthProperties} 是启动时绑定的，
 *       改 yml 不重启不生效 —— 一个「登录功能」连改密码都做不到，
 *       只能算入口占位，不算功能</li>
 *   <li><b>登出是假的</b>。写死的 token 永不变化，服务端没有可撤销的东西，
 *       {@code logout} 只能清前端 localStorage，token 本身依然有效</li>
 * </ol>
 * 落库 + Sa-Token 之后这两件事都变成真的：密码可改（改库即生效），
 * 登出即失效（会话在 Redis 里被删掉）。
 *
 * <h3>⚠️ 这一期仍然不是权限体系</h3>
 * {@link #roles} 只是「给前端菜单渲染留的占位」，<b>不参与任何鉴权判断</b>。
 * 所有登录用户看到的、能调的都一样 —— 见 {@code auth/AGENTS.md} 铁律 8。
 *
 * @see com.bookcollector.auth.repository.SysUserRepository
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "sys_user")
public class SysUser implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    /**
     * 登录名，<b>唯一</b>（由 {@code uk_username} 索引保证）。
     *
     * <p>存原样大小写，不做归一化：本服务只有一个本地账号，
     * 「Admin 和 admin 算不算同一个人」是个没有收益的判断题。
     */
    private String username;

    /**
     * 密码摘要：{@link com.bookcollector.util.Md5Util#md5(String)} 的
     * 32 位小写十六进制。<b>库里永远不存明文。</b>
     *
     * <p>字段名刻意不叫 {@code password} 之外的任何名字（比如 {@code pwd}），
     * 是为了让 {@code db.sys_user.find()} 的输出一眼能看出这是密码字段 ——
     * 排查时最怕的就是看不出哪列是敏感的。
     */
    private String password;

    /** 展示名。登录后前端顶栏显示它，为空时回退成 {@link #username} */
    private String nickname;

    /**
     * 角色列表。<b>占位字段，不参与鉴权</b>。
     *
     * <p>保留它是因为前端菜单已经按 {@code roles} 渲染（`/api/auth/me` 的契约），
     * 删掉会牵动前端；但后端不做任何 {@code hasRole} 判断。
     */
    private List<String> roles;

    /**
     * 是否启用。{@code false} 时登录直接被拒。
     *
     * <p>用包装类型 {@code Boolean} 而不是 {@code boolean}：历史数据可能没有这个字段，
     * {@code null} 需要能被区分出来（见 {@code AuthServiceImpl} 的判定 ——
     * 按「不显式停用即启用」处理，避免给老数据补字段）。
     */
    private Boolean enabled;

    /** 创建时间 */
    private Date createdAt;

    /**
     * 最后一次登录成功的时间。
     *
     * <p>刻意在<b>登录成功后</b>才更新，失败不更新 —— 否则「最近登录时间」
     * 会变成「最近尝试登录时间」，失去「这个账号还在用吗」的判断价值。
     */
    private Date lastLoginAt;
}
