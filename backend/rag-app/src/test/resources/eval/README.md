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

报告写到 `backend/rag-app/target/eval-report.md`。设置 `EVAL_REWRITE=false` 可以关闭“低分时改写问题再检索”，用来对比改写前后的误拒和延迟。先离线检查格式和流程（不调真实模型、分数没有意义）时，再加上 `EVAL_EMBEDDING_PROVIDER=hashing EVAL_RERANK_PROVIDER=lexical EVAL_CHAT_PROVIDER=extractive`，`MODEL_API_KEY` 随便填一个值即可。

## 开源数据集（CRUD-RAG）

用中文新闻问答数据集 [CRUD-RAG](https://github.com/IAAR-Shanghai/CRUD_RAG) 跑大规模评测，覆盖单篇、两篇、三篇新闻综合回答，以及原文被移出语料的应拒答题。新闻版权属于原媒体，数据只在运行时下载、转换，不进仓库。在 GitHub Actions 里手动运行 `eval-public` 工作流即可；本地运行：

```sh
curl -sSfL -o /tmp/split_merged.json \
  https://raw.githubusercontent.com/IAAR-Shanghai/CRUD_RAG/f219391e46944fc838e4fe0f77b2fcde140f75e2/data/crud_split/split_merged.json
python3 scripts/eval/crud_rag.py /tmp/split_merged.json /tmp/crud-eval   # 加 --one 4 --two 3 --three 3 --unanswerable 3 --distractors 20 先小规模试跑
MODEL_API_KEY=... EVAL_DATA_DIR=/tmp/crud-eval ./mvnw verify -pl backend/rag-app -am \
  -Dtest.groups=eval -Dtest.excludedGroups=none -Dtest=EvaluationRun -Dsurefire.failIfNoSpecifiedTests=false
```

题目格式：`expectedDocuments` 列出原文所在的文档（任一篇进前 10 算召回，全部进前 10 另计“多篇原文全部召回”），`referenceAnswer` 是参考答案。带参考答案的题，回答交给评判模型对照判断为正确、部分正确或错误：

- `EVAL_JUDGE_MODEL`：评判模型，默认 `THUDM/GLM-4-9B-0414`。
- `EVAL_JUDGE_BASE_URL`：OpenAI 兼容接口地址，默认同 `MODEL_BASE_URL`；可以指向自己部署的专用评判模型。
- `EVAL_JUDGE_API_KEY`：默认用 `MODEL_API_KEY`。
- `EVAL_JUDGE_PROVIDER=none`：不评判，离线试跑时用。

评判也会判错，结论用于校准门槛前先人工抽查。换评判模型或改评判提示词后，先手动运行 `judge-probe` 工作流做评判自检：样本由 `scripts/eval/judge_probe.py` 从 CRUD-RAG 生成，回答用参考答案构造（原样、补一句原文真话、只留前一半、换成别题答案），结论事先知道，报告给出每类的准确率。

## 真实问题 → 评测题

1. 管理员在“回答记录”页选“差评”或“答不上来”，点“导出为评测题草稿”。
2. 逐题按 `note` 的提示标注：确认 `kind`，填好 `expectedDocument` 和 `keywords`。没标完的可回答题会让评测直接失败并指出是哪一题。
3. 把标好的题合并进私有评测集的 `cases.json`，需要的资料放进 `corpus/store/`。
