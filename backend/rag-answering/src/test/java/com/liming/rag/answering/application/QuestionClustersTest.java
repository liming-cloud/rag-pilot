package com.liming.rag.answering.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 知识缺口归类（D-047）：用固定向量检验同义问法归一类、无关问题不合并。 */
class QuestionClustersTest {

    private static float[] v(float... values) {
        return values;
    }

    @Test
    @DisplayName("同义问法（向量相近）归一类，无关问题不合并；类里保持输入顺序")
    void groupsSimilarQuestions() {
        var vectors = List.of(
                v(1, 0, 0),          // 营业到几点
                v(0, 1, 0),          // 餐补怎么报销
                v(0.95f, 0.1f, 0),   // 晚上几点关门（与第 1 个相似度约 0.99）
                v(0, 0.9f, 0.3f),    // 餐补报销流程（与第 2 个约 0.95）
                v(0, 0, 1));         // 冷柜报修

        assertThat(QuestionClusters.bySimilarity(vectors, 0.85))
                .containsExactly(List.of(0, 2), List.of(1, 3), List.of(4));
    }

    @Test
    @DisplayName("低于门槛的不合并；一个问题与两类都相近时并入更像的那一类")
    void respectsThresholdAndPicksClosest() {
        var vectors = List.of(v(1, 0), v(0, 1), v(0.6f, 0.8f));

        assertThat(QuestionClusters.bySimilarity(vectors, 0.85)).hasSize(3);
        assertThat(QuestionClusters.bySimilarity(vectors, 0.5))
                .containsExactly(List.of(0), List.of(1, 2));
    }

    @Test
    @DisplayName("退回按原文分组：去掉空白、标点、全半角和大小写差异后相同的才算一类")
    void groupsByNormalizedText() {
        var questions = List.of("门店几点打烊？", "门店 几点打烊?", "晚上几点关门", "WiFi 密码", "wifi密码！");

        assertThat(QuestionClusters.byText(questions))
                .containsExactly(List.of(0, 1), List.of(2), List.of(3, 4));
    }

    @Test
    @DisplayName("零向量不会让计算出错，自成一类")
    void handlesZeroVector() {
        assertThat(QuestionClusters.bySimilarity(List.of(v(0, 0), v(0, 0)), 0.85)).hasSize(2);
    }
}
