package com.liming.rag;

import com.liming.rag.ai.EmbeddingClient;
import com.liming.rag.evaluation.api.EvalCase;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识缺口归类门槛的校准（D-047）：用评测集里同一道题的不同问法（如“临期折扣”和“临期折扣-错字”“有效期-临期折扣”）
 * 作为应该归一类的问题对，其余问题两两作为不该合并的问题对，用当前嵌入模型算余弦相似度，给出各个门槛下
 * 同义问法能合并的比例和会误合并的对数。只报告，不影响评测指标。
 */
final class GapCalibration {

    static final List<Double> THRESHOLDS = List.of(0.70, 0.75, 0.80, 0.85, 0.90, 0.95);

    private GapCalibration() {
    }

    record Pair(String a, String b, double similarity) {
    }

    static String report(List<EvalCase> cases, EmbeddingClient embeddings) {
        // 追问和寒暄依赖上下文，单独看不是完整的问题，不参与。
        var usable = cases.stream()
                .filter(evalCase -> evalCase.history().isEmpty() && evalCase.kind() != EvalCase.Kind.SMALL_TALK)
                .toList();
        var ids = usable.stream().map(EvalCase::id).collect(Collectors.toSet());
        var questions = usable.stream().map(EvalCase::question).toList();
        List<float[]> vectors;
        try {
            vectors = embeddings.embed(questions);
        } catch (RuntimeException e) {
            return "\n### 知识缺口归类门槛校准\n\n嵌入模型不可用，这次没有校准：" + e.getMessage() + "\n";
        }
        var synonyms = new ArrayList<Pair>();
        var unrelated = new ArrayList<Pair>();
        for (int i = 0; i < usable.size(); i++) {
            for (int j = i + 1; j < usable.size(); j++) {
                var pair = new Pair(questions.get(i), questions.get(j), cosine(vectors.get(i), vectors.get(j)));
                boolean same = family(usable.get(i).id(), ids).equals(family(usable.get(j).id(), ids));
                (same ? synonyms : unrelated).add(pair);
            }
        }
        return markdown(synonyms, unrelated);
    }

    /** 同一道题的不同问法：id 去掉“-口语”这类后缀，或去掉“有效期-”这类前缀后，是另一道题的 id。 */
    static String family(String id, Set<String> ids) {
        var dash = id.indexOf('-');
        if (dash < 0) {
            return id;
        }
        var prefix = id.substring(0, dash);
        var suffix = id.substring(dash + 1);
        return ids.contains(prefix) ? prefix : ids.contains(suffix) ? suffix : id;
    }

    private static String markdown(List<Pair> synonyms, List<Pair> unrelated) {
        var text = new StringBuilder("\n### 知识缺口归类门槛校准\n\n");
        text.append("同义问法 ").append(synonyms.size()).append(" 对，其余问题两两 ").append(unrelated.size())
                .append(" 对，按当前嵌入模型的余弦相似度统计（D-047）。\n\n");
        text.append("| 门槛 | 同义问法合并 | 误合并的对数 |\n| --- | --- | --- |\n");
        for (var threshold : THRESHOLDS) {
            long merged = synonyms.stream().filter(pair -> pair.similarity() >= threshold).count();
            long wrong = unrelated.stream().filter(pair -> pair.similarity() >= threshold).count();
            text.append(String.format(Locale.ROOT, "| %.2f | %d / %d | %d |%n", threshold, merged, synonyms.size(),
                    wrong));
        }
        text.append("\n同义问法相似度最低的 5 对：\n\n");
        synonyms.stream().sorted(Comparator.comparingDouble(Pair::similarity)).limit(5)
                .forEach(pair -> text.append(line(pair)));
        text.append("\n不相关问题相似度最高的 5 对：\n\n");
        unrelated.stream().sorted(Comparator.comparingDouble(Pair::similarity).reversed()).limit(5)
                .forEach(pair -> text.append(line(pair)));
        return text.toString();
    }

    private static String line(Pair pair) {
        return String.format(Locale.ROOT, "- %.3f：%s ／ %s%n", pair.similarity(), pair.a().replace("|", "／"),
                pair.b().replace("|", "／"));
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return normA == 0 || normB == 0 ? 0 : dot / Math.sqrt(normA * normB);
    }
}
