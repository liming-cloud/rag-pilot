/**
 * 限界上下文：系统设置。现在是模型管理——服务商（地址、加密保存的密钥）、各用途用哪个模型、变更记录（D-043、D-044）；
 * 通过实现 rag-ai 的 {@code ModelRouting} 让保存后的配置在下一次模型调用时生效。
 *
 * <p>非核心领域，按普通 Service 加 MyBatis Mapper 实现，不建聚合。模型配置是部署级的，不分租户。
 */
package com.liming.rag.settings;
