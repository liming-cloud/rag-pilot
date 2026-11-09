package com.liming.rag.ai.common;

/** 各能力的服务商配置校验共用。 */
public final class Providers {

    private Providers() {
    }

    /** {@code rag.ai.<能力>.provider} 填了不认识的值：启动失败。 */
    public static IllegalStateException unknown(String capability, String value, String allowed) {
        return new IllegalStateException("rag.ai." + capability + ".provider 只能是 " + allowed + "，当前为：" + value);
    }
}
