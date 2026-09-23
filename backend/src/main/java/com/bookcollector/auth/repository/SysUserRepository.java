package com.bookcollector.auth.repository;

import com.bookcollector.auth.entity.SysUser;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.util.Date;

/**
 * 登录用户（{@code sys_user}）的 Mongo 访问。
 *
 * <p>用 {@code MongoTemplate} 而不是继承 {@code MongoRepository}：与本项目
 * 其它仓储（{@code BookRepository} / {@code TaxonomyRepository}）保持一致，
 * 且这里只需要三四个方法，接口推导出来的几十个方法都是噪音。
 *
 * <p><b>没有 {@code updatePassword} / {@code delete} 之类的写方法。</b>
 * 这一期不提供「改密码」「加用户」的界面入口（见 {@code auth/AGENTS.md}），
 * 需要时直接改库 —— 不预留没有调用方的接口。
 */
@Repository
public class SysUserRepository {

    private final MongoTemplate mongoTemplate;

    public SysUserRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * 按登录名查用户。
     *
     * @return 不存在时返回 {@code null}（而不是抛异常）—— 登录逻辑需要
     *         把「用户不存在」和「密码不对」走同一条失败分支，
     *         见 {@code auth/AGENTS.md} 铁律 5
     */
    public SysUser findByUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            return null;
        }
        return mongoTemplate.findOne(
                Query.query(Criteria.where("username").is(username.trim())),
                SysUser.class);
    }

    /** 是否已存在该登录名。供 {@code SeedRunner} 做幂等判断 */
    public boolean existsByUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            return false;
        }
        return mongoTemplate.exists(
                Query.query(Criteria.where("username").is(username.trim())),
                SysUser.class);
    }

    public SysUser save(SysUser user) {
        return mongoTemplate.save(user);
    }

    /**
     * 刷新最后登录时间。
     *
     * <p>用 {@code $set} 定向更新而不是「查出来 → 改字段 → save 整个对象」：
     * 后者会把整条文档重写一遍，如果期间有人在库里手工改了别的字段，
     * 会被这次登录顺手覆盖掉。定向更新只碰这一个字段。
     *
     * @return 是否真的更新到了（用户被删掉时返回 false，不抛异常 ——
     *         登录已经在前面成功了，这里失败不该把登录结果变成错误）
     */
    public boolean touchLastLogin(String userId, Date at) {
        if (userId == null || userId.trim().isEmpty() || at == null) {
            return false;
        }
        Update update = new Update().set("lastLoginAt", at);
        return mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(userId.trim())),
                update,
                SysUser.class).getModifiedCount() > 0;
    }
}
