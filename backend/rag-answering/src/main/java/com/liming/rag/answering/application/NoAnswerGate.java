package com.liming.rag.answering.application;

import java.util.function.Consumer;

/**
 * 流式输出的闸门：先攒住开头的文字，确认不是 NO_ANSWER 之后再放行；放行之后也过滤掉夹在中间或末尾的 NO_ANSWER，
 * 末尾可能是标记开头的几个字先留着，等下一段或 {@link #flush()} 再决定。
 * {@code onModelDelta} 在收到模型的任何片段时调用（包括被拦下的），用来记模型首字时间。
 */
final class NoAnswerGate implements Consumer<String> {

    private final Consumer<String> downstream;
    private final Runnable onModelDelta;
    private final StringBuilder head = new StringBuilder();
    private final StringBuilder pending = new StringBuilder();
    private boolean open;
    private boolean declined;

    NoAnswerGate(Consumer<String> downstream, Runnable onModelDelta) {
        this.downstream = downstream;
        this.onModelDelta = onModelDelta;
    }

    @Override
    public void accept(String delta) {
        onModelDelta.run();
        if (open) {
            forward(delta);
            return;
        }
        if (declined) {
            return;
        }
        head.append(delta);
        var start = head.toString().stripLeading();
        if (start.startsWith(AnswerPrompt.NO_ANSWER)) {
            declined = true;
        } else if (!AnswerPrompt.NO_ANSWER.startsWith(start)) {
            open = true;
            forward(head.toString());
        }
    }

    /** 生成结束时把留着的几个字放出去（它们最终没有组成标记）。 */
    void flush() {
        if (open && !pending.isEmpty()) {
            downstream.accept(pending.toString());
            pending.setLength(0);
        }
    }

    private void forward(String delta) {
        pending.append(delta);
        var text = pending.toString().replace(AnswerPrompt.NO_ANSWER, "");
        int keep = markerPrefixAtEnd(text);
        var ready = text.substring(0, text.length() - keep);
        pending.setLength(0);
        pending.append(text, text.length() - keep, text.length());
        if (!ready.isEmpty()) {
            downstream.accept(ready);
        }
    }

    /** 文字末尾有几个字可能是标记的开头。 */
    private static int markerPrefixAtEnd(String text) {
        for (int length = Math.min(text.length(), AnswerPrompt.NO_ANSWER.length() - 1); length > 0; length--) {
            if (AnswerPrompt.NO_ANSWER.startsWith(text.substring(text.length() - length))) {
                return length;
            }
        }
        return 0;
    }
}
