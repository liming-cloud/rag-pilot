# D-020 入库与问答流程按 Spring AI 接口组织（2026-10-27）

流程骨架用 Spring AI 的阶段接口，实现自己写：入库为 `DocumentReader`（rag-ai 的 `DocumentReaders`）→ `DocumentTransformer`（标题感知分块）→ `DocumentWriter`（向量化并写入 chunk）；问答侧之后按 `DocumentRetriever` / `DocumentPostProcessor` / `QueryAugmenter` 改。框架自带的组件（问题改写、Tika 读取器等）可以直接插进来做评测。边界：Spring AI 的 `Document` 只在流程内部使用，不进模块 `api`、不当领域模型，元数据键集中定义；流程编排仍在 `AnswerService`，不用 `RetrievalAugmentationAdvisor`，因为它覆盖不了“相关度低时不调模型直接拒答”和生成后的引用校验。问答侧为此引入 `spring-ai-rag`（版本由 Spring AI BOM 管理），只用它的阶段接口；检索器从 `Query.context` 取提问人身份，缺少时直接拒绝。
