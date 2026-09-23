package com.bookcollector.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * MD5 摘要工具，输出 32 位小写十六进制。
 *
 * <h3>⚠️ 为什么这里可以用 MD5</h3>
 * MD5 早已不适合做密码哈希（无盐、快、有碰撞攻击）。用它的前提必须写清楚，
 * 否则下一个人会把这段代码抄到真正对外的系统里去：
 * <ul>
 *   <li>本服务只绑定 {@code 127.0.0.1}，单用户本地使用（2.5 非功能需求），
 *       攻击者能碰到 {@code sys_user} 集合，就已经拿到了 MongoDB 的本地访问权，
 *       此时密码摘要是什么算法都不再是防线</li>
 *   <li>目标不是「抗爆破」，而是「<b>不落明文</b>」—— 避免密码出现在
 *       数据库备份、日志、以及随手 {@code db.sys_user.find()} 的屏幕上</li>
 * </ul>
 * 一旦要对外暴露，必须换成 bcrypt / argon2 这类带盐、可调工作量的算法，
 * 并把 {@link #md5(String)} 的调用点全部收拢到 {@code AuthServiceImpl} 一处
 * （它目前是唯一调用方，换起来只动一个文件）。
 *
 * <h3>为什么不用 {@code DigestUtils}（commons-codec）</h3>
 * 只为一个 8 行的方法引一个新依赖不划算，而且 {@code DigestUtils.md5Hex}
 * 的大小写/编码行为需要额外确认。这里显式写死 UTF-8 + 小写，不留解释空间。
 */
public final class Md5Util {

    /** 十六进制字符表。小写 —— 与 {@code DigestUtils.md5Hex} 的输出一致 */
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Md5Util() {
    }

    /**
     * 计算 MD5 摘要。
     *
     * @param raw 明文。<b>null 返回 null</b>（而不是抛异常或返回空串）——
     *            调用方在做「账号不存在」判断时，能自然地把 null 摘要
     *            当成一个永远不匹配的值，不必额外判空
     * @return 32 位小写十六进制；入参为 null 时返回 null
     */
    public static String md5(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            // 逐字节手工转 hex：不用 String.format("%02x") 是因为后者每个字节
            // 都要新建一个 Formatter，在登录路径上没必要
            char[] out = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                int v = bytes[i] & 0xFF;
                out[i * 2] = HEX[v >>> 4];
                out[i * 2 + 1] = HEX[v & 0x0F];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            // JDK 8 必然内置 MD5（JLS 要求）。走到这里说明 JRE 被裁剪过，
            // 属于部署环境坏了，不是业务错误 —— 抛出去让启动/请求显式失败，
            // 不要静默降级成「所有密码都校验通过」
            throw new IllegalStateException("当前 JRE 不支持 MD5 算法", e);
        }
    }

    /**
     * 校验明文与已存摘要是否一致。
     *
     * <p>注意是「先摘要再比较」，不是「比较明文」—— 库里永远只有摘要。
     */
    public static boolean matches(String raw, String encoded) {
        if (raw == null || encoded == null) {
            return false;
        }
        return encoded.equals(md5(raw));
    }
}
