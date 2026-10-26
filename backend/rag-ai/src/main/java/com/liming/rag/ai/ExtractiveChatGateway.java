package com.liming.rag.ai;

import java.util.regex.Pattern;

/**
 * 本地确定性“对话模型”：摘出用户消息里第 1 条资料的第一句话并标注 [1]；没有资料时回复 NO_ANSWER。
 * 只用于测试和离线开发，约定的资料格式见 rag-answering 的提示词。
 */
final class ExtractiveChatGateway implements ChatGateway {

    private static final Pattern FIRST_EVIDENCE = Pattern.compile("^\\[1\\][^\\n]*\\n([^\\n]+)", Pattern.MULTILINE);

    @Override
    public String complete(String systemPrompt, String userMessage) {
        var matcher = FIRST_EVIDENCE.matcher(userMessage);
        if (!matcher.find()) {
            return "NO_ANSWER";
        }
        var line = matcher.group(1).strip();
        int end = line.indexOf('。');
        return (end >= 0 ? line.substring(0, end + 1) : line) + "[1]";
    }
}
