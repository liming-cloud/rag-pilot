/**
 * 限界上下文：系统设置。
 *
 * <ul>
 *   <li>模型管理——服务商（地址、加密保存的密钥）、各用途用哪个模型、变更记录（D-043、D-044）；通过实现 rag-ai 的
 *       {@code ModelRouting} 让保存后的配置在下一次模型调用时生效。</li>
 *   <li>行业包与系统设置（D-045）——启动时读 {@code industry-packs/<名字>/pack.yml}；品牌、问答页文案、推荐问题、
 *       拒答话术在界面保存后以界面为准。问答经 api 包的 {@code AnsweringSettings} 读行业包和拒答话术，各上下文经 {@code StatsZone} 读统计时区。</li>
 * </ul>
 *
 * <p>非核心领域，按普通 Service 加 MyBatis Mapper 实现，不建聚合。都是部署级的，不分租户。
 */
package com.liming.rag.settings;
