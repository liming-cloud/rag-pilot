package com.liming.rag.ai;

import java.util.Optional;

/**
 * 各用途当前用哪个服务商和模型，由系统设置模块（rag-settings）实现（D-044）。每次模型调用前都问一次，
 * 所以在管理端保存后下一次请求就生效；返回空表示这个用途没在界面配置过，用环境变量里的配置。
 * 本地确定性实现（离线开发、测试用）不受它影响。
 */
public interface ModelRouting {

    Optional<ModelRoute> override(ModelUsage usage);
}
