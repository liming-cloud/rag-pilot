package com.liming.rag.settings.application.system;

import java.util.List;

/** 问答页的文案和推荐问题。 */
public record ChatTexts(String placeholder, String emptyHint, List<String> suggestedQuestions) {
}
