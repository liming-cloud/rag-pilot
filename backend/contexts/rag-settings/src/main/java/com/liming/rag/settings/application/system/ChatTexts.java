package com.liming.rag.settings.application.system;

import java.util.List;

/**
 * 问答页的文案和推荐问题。
 *
 * @param refusalTips 答不上来时给的“换个问法”建议，来自行业包，不能在界面改
 * @param refusalContact 答不上来时该问谁的话术，来自行业包，没写时为空
 */
public record ChatTexts(String placeholder, String emptyHint, List<String> suggestedQuestions,
        List<String> refusalTips, String refusalContact) {
}
