package com.liming.rag.settings.application;

import java.util.List;

/**
 * 内核默认值（D-042、D-045）：部署和行业包都没给时用，不带任何行业内容。品牌三项与前端 lib/branding.ts 的兜底一致，
 * 问答页两项与前端 features/chat/api.ts 的兜底一致。
 */
final class KernelDefaults {

    static final String BRAND_NAME = "明答";
    static final String BRAND_TAGLINE = "每个回答，都有出处";
    static final String LOGIN_HINT = "账号由管理员开通；忘记密码请联系管理员。";
    static final String CHAT_PLACEHOLDER = "输入问题…";
    static final String CHAT_EMPTY_HINT = "问问知识库里的内容，回答会标出原文出处，可以接着追问。";
    static final String REFUSAL_MESSAGE = "知识库里没有找到能回答这个问题的可靠依据，建议换个问法，或联系管理员确认。";

    static final String ANSWER_ROLE = "你是企业的运营助手，回答员工的问题。";
    static final String CITATION_EXAMPLE = "报销单需在每月 25 日前提交[2]。";
    static final String REWRITE_ROLE = "你负责把员工的提问改写成便于检索制度文件的规范问题。";

    private KernelDefaults() {
    }

    static List<String> of(SettingKey key) {
        return switch (key) {
            case BRAND_NAME -> List.of(BRAND_NAME);
            case BRAND_TAGLINE -> List.of(BRAND_TAGLINE);
            case LOGIN_HINT -> List.of(LOGIN_HINT);
            case CHAT_PLACEHOLDER -> List.of(CHAT_PLACEHOLDER);
            case CHAT_EMPTY_HINT -> List.of(CHAT_EMPTY_HINT);
            case SUGGESTED_QUESTIONS -> List.of();
            case REFUSAL_MESSAGE -> List.of(REFUSAL_MESSAGE);
        };
    }
}
