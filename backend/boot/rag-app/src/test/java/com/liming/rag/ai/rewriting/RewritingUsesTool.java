package com.liming.rag.ai.rewriting;

import com.liming.rag.ai.tool.ToolCall;

/** 违规样例：一种能力的子包直接引用另一种能力的类型（共用的应下沉到 common）。 */
public class RewritingUsesTool {

    ToolCall call;
}
