package com.liming.rag.settings.infrastructure.persistence;

/** model_provider 的一行（#136）：MyBatis 按列名（下划线转驼峰）直接给字段赋值，只在 infrastructure 包内读取。 */
final class ModelProviderDO {

    Long id;
    String name;
    String baseUrl;
    byte[] apiKeyCipher;
    String apiKeyLast4;
}
