package com.liming.rag.evaluation.infrastructure.ai;

import com.liming.rag.ai.UsageAttribution;
import com.liming.rag.ai.common.ModelCallUsage;
import com.liming.rag.evaluation.application.EvaluationUsage;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** 实现 {@link EvaluationUsage}：经 rag-ai 的用量归属，把期间的模型调用记为 {@link ModelCallUsage#EVALUATION}。 */
@Component
class EvaluationUsageAttribution implements EvaluationUsage {

    private final UsageAttribution attribution;

    EvaluationUsageAttribution(UsageAttribution attribution) {
        this.attribution = attribution;
    }

    @Override
    public <T> T attribute(Supplier<T> work) {
        return attribution.attribute(ModelCallUsage.EVALUATION, work);
    }
}
