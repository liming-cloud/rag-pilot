package com.liming.rag.answering.application.insight;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 把问题按意思归类（D-047）：按给定顺序（出现次数多的在前）逐个处理，与已有各类的第一个问题（种子）算余弦相似度，
 * 最高的达到门槛就并入那一类，否则自成一类。结果只取决于输入顺序和向量，便于测试；不调用对话模型。
 */
final class QuestionClusters {

    private QuestionClusters() {
    }

    /**
     * @param vectors 与问题同序的向量
     * @return 每一类里问题的下标，类按第一个问题的下标排序，类里保持输入顺序
     */
    static List<List<Integer>> bySimilarity(List<float[]> vectors, double threshold) {
        var seeds = new ArrayList<float[]>();
        var clusters = new ArrayList<List<Integer>>();
        for (int i = 0; i < vectors.size(); i++) {
            var vector = normalized(vectors.get(i));
            int best = -1;
            double bestScore = threshold;
            for (int c = 0; c < seeds.size(); c++) {
                double score = dot(vector, seeds.get(c));
                if (score >= bestScore) {
                    best = c;
                    bestScore = score;
                }
            }
            if (best < 0) {
                seeds.add(vector);
                clusters.add(new ArrayList<>(List.of(i)));
            } else {
                clusters.get(best).add(i);
            }
        }
        return clusters;
    }

    /** 嵌入模型不可用时的退路：去掉空白、标点和大小写差异后完全相同的才算一类。 */
    static List<List<Integer>> byText(List<String> questions) {
        var keys = new ArrayList<String>();
        var clusters = new ArrayList<List<Integer>>();
        for (int i = 0; i < questions.size(); i++) {
            var key = textKey(questions.get(i));
            int found = keys.indexOf(key);
            if (found < 0) {
                keys.add(key);
                clusters.add(new ArrayList<>(List.of(i)));
            } else {
                clusters.get(found).add(i);
            }
        }
        return clusters;
    }

    static String textKey(String question) {
        return Normalizer.normalize(question, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{P}\\p{S}]", "");
    }

    static double cosine(float[] a, float[] b) {
        return dot(normalized(a), normalized(b));
    }

    private static float[] normalized(float[] vector) {
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        var result = new float[vector.length];
        if (norm == 0) {
            return result;
        }
        for (int i = 0; i < vector.length; i++) {
            result[i] = (float) (vector[i] / norm);
        }
        return result;
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
