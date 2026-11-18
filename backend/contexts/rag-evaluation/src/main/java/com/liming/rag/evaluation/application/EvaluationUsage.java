package com.liming.rag.evaluation.application;

import java.util.function.Supplier;

/** 把试问和运行期间的模型用量记为“评测”用途（#224、D-069），实现在 infrastructure/ai。 */
public interface EvaluationUsage {

    <T> T attribute(Supplier<T> work);
}
