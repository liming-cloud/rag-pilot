package com.liming.rag.settings.application.model;

import org.jspecify.annotations.Nullable;

/**
 * 一个用途在界面上保存的路由，经 {@link ModelRouteCache} 缓存：没在界面配置过时 {@code setting} 为 null；{@code provider}
 * 是它用的服务商（密钥是密文），用环境变量的服务商或服务商已被删除时为 null。
 */
public record SavedRoute(@Nullable PurposeSetting setting, @Nullable ModelProvider provider) {
}
