package com.bookcollector.setting.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.util.Date;

/**
 * 系统配置 KV。
 *
 * <p>第一期只在「设置」页放少量运行时可调项，例如：
 * <ul>
 *   <li>{@code defaultMaxPages} —— 新建任务时的默认最大页数（原 config.py 里是 10）</li>
 *   <li>{@code requestDelayMs} —— 每页请求间隔（原 config.py 里是 1 秒）</li>
 * </ul>
 *
 * <p><b>注意</b>：登录用的账号密码<b>不</b>放这里 —— 它们落在 {@code sys_user} 集合，
 * 密码存 <b>MD5 摘要</b>而非明文；初始管理员账号由 {@code SeedRunner} 首次启动时
 * 从 {@code application.yml} 的 {@code bookcollector.auth.*} 播种进去。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "settings")
public class Setting implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;

    /** 配置键，唯一 */
    private String key;

    /** 配置值。统一存字符串，读取时按需转换 */
    private String value;

    /** 说明 */
    private String remark;

    @LastModifiedDate
    private Date updatedAt;
}
