package com.liming.rag.ai;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;

/**
 * 本地确定性向量：把相邻两个字符的组合散列到固定维度，再归一化。
 *
 * <p>只用于测试和没有模型密钥时的离线开发。字面相近的文本向量也相近，足以验证检索链路，
 * 但没有语义能力，不能用来评估召回指标。
 */
final class HashingEmbeddingClient implements EmbeddingClient {

    @Override
    public List<float[]> embed(List<String> texts) {
        return texts.stream().map(HashingEmbeddingClient::vectorOf).toList();
    }

    static float[] vectorOf(String text) {
        var vector = new float[DIMENSIONS];
        var codePoints = text.codePoints().filter(c -> !Character.isWhitespace(c)).toArray();
        for (int i = 0; i < codePoints.length; i++) {
            var gram = i + 1 < codePoints.length
                    ? new String(codePoints, i, 2)
                    : new String(codePoints, i, 1);
            var crc = new CRC32();
            crc.update(gram.getBytes(StandardCharsets.UTF_8));
            vector[(int) (crc.getValue() % DIMENSIONS)] += 1f;
        }
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm > 0) {
            float scale = (float) (1 / Math.sqrt(norm));
            for (int i = 0; i < vector.length; i++) {
                vector[i] *= scale;
            }
        }
        return vector;
    }
}
