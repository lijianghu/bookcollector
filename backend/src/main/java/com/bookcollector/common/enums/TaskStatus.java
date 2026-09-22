package com.bookcollector.common.enums;

/**
 * 任务 / 运行状态。
 *
 * <pre>
 *         ┌──────────┐
 *         │ PENDING  │  新建
 *         └────┬─────┘
 *              │ start
 *         ┌────▼─────┐  pause   ┌──────────┐
 *         │ RUNNING  ├─────────►│  PAUSED  │
 *         └────┬─────┘◄─────────┴────┬─────┘
 *              │      resume         │ cancel
 *    ┌─────────┼─────────┐           │
 *    │         │         │           │
 * ┌──▼───┐ ┌───▼───┐ ┌───▼──────┐ ┌──▼───────┐
 * │SUCCESS│ │FAILED │ │CANCELED  │ │CANCELED  │
 * └──────┘ └───────┘ └──────────┘ └──────────┘
 *
 * JVM 重启时，RUNNING / PAUSED 的任务 → INTERRUPTED（游标保留，可手动恢复）
 * </pre>
 *
 * <p>注意 {@link #INTERRUPTED} <b>不是</b>终态：它表示「进程被杀了，但游标还在」，
 * 用户可以直接恢复。只有 {@link #SUCCESS} / {@link #FAILED} / {@link #CANCELED} 才是终态。
 */
public enum TaskStatus {

    /** 待执行 */
    PENDING("待执行"),

    /** 运行中 */
    RUNNING("运行中"),

    /** 已暂停（游标保留） */
    PAUSED("已暂停"),

    /** 已完成 */
    SUCCESS("已完成"),

    /** 失败（重试耗尽或不可恢复错误） */
    FAILED("失败"),

    /** 已取消（用户主动） */
    CANCELED("已取消"),

    /** 已中断（JVM 重启导致，可恢复） */
    INTERRUPTED("已中断");

    private final String label;

    TaskStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 是否终态。终态不可再推进。 */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELED;
    }

    /** 是否处于「占用采集线程」的活跃态。同一 target 同时只允许一个活跃 run。 */
    public boolean isActive() {
        return this == RUNNING || this == PAUSED;
    }

    /** 是否可被 start / resume 拉起 */
    public boolean isResumable() {
        return this == PENDING || this == PAUSED || this == INTERRUPTED;
    }

    public static TaskStatus of(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("status 不能为空");
        }
        String v = value.trim().toUpperCase();
        for (TaskStatus s : values()) {
            if (s.name().equals(v)) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知的 status：" + value);
    }
}
