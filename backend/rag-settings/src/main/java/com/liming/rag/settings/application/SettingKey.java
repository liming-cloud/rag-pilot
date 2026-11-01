package com.liming.rag.settings.application;

/**
 * 系统设置里能在界面改的项（D-045）。品牌三项的默认值来自部署配置，其余来自行业包；都没有时用内核默认值。
 * 长度上限与前端 features/settings/api.ts 一致；品牌三项沿用 D-042 的上限。
 */
public enum SettingKey {
    BRAND_NAME(30, false),
    BRAND_TAGLINE(60, false),
    LOGIN_HINT(100, false),
    CHAT_PLACEHOLDER(40, false),
    CHAT_EMPTY_HINT(100, false),
    /** 一组短句，最多 {@link #MAX_QUESTIONS} 个，可以一个都没有。 */
    SUGGESTED_QUESTIONS(40, true),
    REFUSAL_MESSAGE(150, false);

    public static final int MAX_QUESTIONS = 4;

    private final int maxLength;
    private final boolean list;

    SettingKey(int maxLength, boolean list) {
        this.maxLength = maxLength;
        this.list = list;
    }

    /** 文字最多几个字；列表是每一条最多几个字。 */
    public int maxLength() {
        return maxLength;
    }

    public boolean list() {
        return list;
    }

    public boolean brand() {
        return this == BRAND_NAME || this == BRAND_TAGLINE || this == LOGIN_HINT;
    }
}
