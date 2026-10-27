# 评测集

全部是为虚构连锁品牌“鲜邻便利”编写的合成资料，不含任何真实客户数据，可以放在公开仓库。

- `corpus/store/`：门店运营知识库，评测问题都在这个库里问。
- `corpus/restricted/`：店长专用知识库，用于检查越权泄漏，任何检索结果或引用都不能来自这里。
- `cases.json`：评测题。`answerable` 题给出应当引用的文档和答案关键词（命中任一即可）；`unanswerable` 题应当拒答，其中标了 `restricted` 的题答案只存在于店长专用库。

`style` 标出问法，报告按它分别统计召回、引用正确和误拒：`原话`（不填时的默认值）、`改写`（同义改写）、`口语`、`错字`、`近似`（话题相近但资料里没有答案的拒答题）、`真实`（来自员工的真实提问）。

## 私有评测集（客户真实资料）

客户的资料和问题不进本仓库。把它们放在仓库外的目录里，结构与这里相同（`corpus/restricted/` 可以没有；语料可以是 PDF、Word、Excel、TXT、Markdown），运行评测时用 `EVAL_DATA_DIR` 指过去：

```sh
MODEL_API_KEY=... EVAL_DATA_DIR=/path/to/private-eval ./mvnw verify -pl backend/rag-app -am \
  -Dtest.groups=eval -Dtest.excludedGroups=none -Dtest=EvaluationRun -Dsurefire.failIfNoSpecifiedTests=false
```

报告写到 `backend/rag-app/target/eval-report.md`。先离线检查格式和流程（不调真实模型、分数没有意义）时，再加上 `EVAL_EMBEDDING_PROVIDER=hashing EVAL_RERANK_PROVIDER=lexical EVAL_CHAT_PROVIDER=extractive`，`MODEL_API_KEY` 随便填一个值即可。

## 真实问题 → 评测题

1. 管理员在“回答记录”页选“差评”或“答不上来”，点“导出为评测题草稿”。
2. 逐题按 `note` 的提示标注：确认 `kind`，填好 `expectedDocument` 和 `keywords`。没标完的可回答题会让评测直接失败并指出是哪一题。
3. 把标好的题合并进私有评测集的 `cases.json`，需要的资料放进 `corpus/store/`。
