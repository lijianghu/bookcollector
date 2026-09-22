package com.bookcollector.common.enums;

/**
 * 采集目标类型。
 *
 * <p>决定 URL 的拼法（见 {@code WereadClient#buildUrl}）：
 * <ul>
 *   <li>{@link #CATEGORY} → {@code /{categoryId}?maxIndex={n}}</li>
 *   <li>{@link #RANKING}  → {@code /{rankingType}?maxIndex={n}&rank=1}</li>
 * </ul>
 *
 * <p>放在 {@code common.enums} 而不是 {@code collector} 包，是为了让
 * {@code book.entity} / {@code task.entity} 也能引用它，而不产生
 * 「entity 反向依赖 collector」的循环。
 */
public enum TargetType {

    /** 分类榜，targetId 形如 "300000" */
    CATEGORY("分类"),

    /** 榜单，targetId 形如 "rising" */
    RANKING("榜单");

    private final String label;

    TargetType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 宽松解析：忽略大小写与首尾空格。
     *
     * @throws IllegalArgumentException 值为空或不认识
     */
    public static TargetType of(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("targetType 不能为空");
        }
        String v = value.trim().toUpperCase();
        for (TargetType t : values()) {
            if (t.name().equals(v)) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知的 targetType：" + value);
    }
}
