# D-044 新增上下文模块 rag-settings（系统设置）（2026-11-01）

#120 接后端时发现模型管理没有合适的家：它要有表、接口和权限点，而 `rag-ai` 是技术模块（扁平、只公开契约、没有持久化层、不依赖上下文模块），放进 identity、knowledge、answering 也都不对。经人决定新增上下文模块 `rag-settings`（系统设置），放模型管理的表、接口和服务；M5 的行业包、品牌名称、推荐问题等系统设置以后也放这里（D-042 暂放在 identity 的品牌配置届时迁过来）。`rag-ai` 只增加三个公开契约：各用途当前用哪个服务商和模型（`ModelRouting`）、连通测试和读服务商模型列表（`ModelProbe`），由 `rag-settings` 实现和调用（D-054 起由 settings 的 `infrastructure` 层实现和调用）；依赖方向是 settings → ai，`rag-ai` 仍然扁平、不依赖上下文模块。模型配置是部署级的，表不带租户。
