# D-033 SQL 只写在 Mapper XML，目录结构规范化并自动检查（2026-10-30）

代码规范评审（#108）经人工决定：保持 domain-forge 的“禁止注解 SQL”。原先 3 个 Mapper 的 18 处 `@Select` 等注解迁到 `mapper/<模块名>/*.xml`（`mapper-locations` 显式配置），条件构造器禁止 `apply`、`last` 等原始 SQL 拼接；长的动态 SQL 在 XML 里更好读，SQL 与 Java 分开评审。`INSERT … RETURNING` 用 `<select>` 取主键，加 `flushCache="true"`，避免同一会话里命中一级缓存。目录结构写进 AGENTS.md：上下文模块只用 api、web、application、domain、infrastructure 五个子包（沿用 `web`，不改成 domain-forge 的 `interfaces`），定下层间依赖方向；技术模块 rag-ai 不拆 api／internal 子包，因为实现类已经是包内可见，拆包反而要把实现改成 public。规则由 `PackageLayoutTest`、`MapperXmlLayoutTest` 和前端 `structure.test.ts` 检查，每条有违规样例。
