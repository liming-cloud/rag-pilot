/**
 * 限界上下文：系统设置。
 *
 * <ul>
 *   <li>模型管理——服务商（地址、加密保存的密钥）、各用途用哪个模型、变更记录（D-043、D-044）；由 infrastructure 层的适配器
 *       实现 rag-ai 的 {@code ModelRouting}，让保存后的配置在下一次模型调用时生效（D-054）。</li>
 *   <li>行业包与系统设置（D-045）——启动时读 {@code industry-packs/<名字>/pack.yml}；品牌、问答页文案、推荐问题、
 *       拒答话术在界面保存后以界面为准。问答经 api 包的 {@code AnsweringSettings} 读行业包和拒答话术，各上下文经 {@code StatsZone} 读统计时区。</li>
 * </ul>
 *
 * <p>非核心领域，按普通 Service 加 MyBatis Mapper 实现，不建聚合。都是部署级的，不分租户。
 */
// 允许依赖的模块（#136）：上下文只能依赖这些模块，以及它们 api 包公开的类型；方向见 规则卡 A-10。
@ApplicationModule(allowedDependencies = {"shared", "persistence :: paging", "cache", "cache :: typed", "ai", "ai :: common", "identity :: api"})
@NullMarked
package com.liming.rag.settings;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
