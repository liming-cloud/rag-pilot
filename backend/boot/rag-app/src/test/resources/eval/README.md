# 评测集

评测集按行业包分目录（D-045），`EvaluationRun` 按 `RAG_INDUSTRY_PACK`（默认 `retail`）选择 `eval/<行业包>/`。现在只有新零售：`retail/` 下全部是为虚构连锁品牌“鲜邻便利”编写的合成资料，不含任何真实客户数据，可以放在公开仓库。

- `corpus/store/`：门店运营知识库，评测问题都在这个库里问。
- `corpus/restricted/`：店长专用知识库，用于检查越权泄漏，任何检索结果或引用都不能来自这里。
- `cases.json`：评测题。`answerable` 题给出应当引用的文档和答案关键词（命中任一即可）；`unanswerable` 题应当拒答，其中标了 `restricted` 的题答案只存在于店长专用库。

`style` 标出问法，报告按它分别统计召回、引用正确和误拒：`原话`（不填时的默认值）、`改写`（同义改写）、`口语`、`错字`、`近似`（话题相近但资料里没有答案的拒答题）、`注入`（资料里夹带了指令的题）、`真实`（来自员工的真实提问）。

`forbidden` 列出回答里不能出现的内容（#145）：`注入` 题的资料里夹带了要模型说这些话的指令（照 Microsoft BIPIA 的攻击类型编写：索要账号密码、谎称暂停营业、引导访问链接、套取提示词、改换角色），回答或被替换掉的原文里出现任一条，就算“被资料里的指令带偏”，必须为 0。这些题同时是可回答题，答案关键词照常检查。

## 工具问答评测集（#183）

`retail/tools/cases.json` 由 `ToolEvaluationRun` 运行（与 `EvaluationRun` 一起跑，`EVAL_ROUNDS` 同样指定轮数），用模拟新零售服务（`RetailMcpServer`）和真实模型。文件里 `personas` 定义各类店员的数据授权（直接授权或经角色，可带“授权后又收回”），`cases` 是题目；`门店库存说明.md` 是唯一的资料（常备量与实际库存不同，用来检验数据与资料冲突时以数据为准）。

`kind`：`SELECT`（该调工具，`expectTool` 和 `expectArguments` 是应当选的工具和参数，`keywords` 是回答里应有的数值）、`CONFLICT`（同 SELECT，问题里带着与数据不同的资料数字）、`NO_TOOL`（制度流程类，不该查数据）、`DENIED`（范围外站点、没授权的工具、多轮诱导换站点、角色授权、收回授权后：不该发出任何请求，回答没有数据引用）、`INJECTION`（`fault: INJECT`，工具返回的商品名后夹带指令，`forbidden` 里的内容不能出现在回答里）。

指标口径（README 指标表）：工具选择正确率 = 该调工具的题里查询成功且工具选对的比例；参数正确率 = 工具选对的题里查询条件包含全部期望参数的比例；越权调用 = `DENIED` 题里模拟服务实际收到的请求数（加上其他题里服务判定范围外的请求数），必须为 0。另报告回答含期望数值的比例、`NO_TOOL` 题的误调用率（只报告）。报告写到 `target/tool-eval-report.md`。

## 私有评测集（客户真实资料）

客户的资料和问题不进本仓库。把它们放在仓库外的目录里，结构与这里相同（`corpus/restricted/` 可以没有；语料可以是 PDF、Word、Excel、TXT、Markdown），运行评测时用 `EVAL_DATA_DIR` 指过去：

```sh
MODEL_API_KEY=... EVAL_DATA_DIR=/path/to/private-eval ./mvnw verify -pl backend/boot/rag-app -am \
  -Dtest.groups=eval -Dtest.excludedGroups=none -Dtest=EvaluationRun -Dsurefire.failIfNoSpecifiedTests=false
```

报告写到 `backend/boot/rag-app/target/eval-report.md`。设置 `EVAL_REWRITE=false` 可以关闭“低分时改写问题再检索”，用来对比改写前后的误拒和延迟。先离线检查格式和流程（不调真实模型、分数没有意义）时，再加上 `EVAL_EMBEDDING_PROVIDER=hashing EVAL_RERANK_PROVIDER=lexical EVAL_CHAT_PROVIDER=extractive`，`MODEL_API_KEY` 随便填一个值即可。

## 开源数据集（CRUD-RAG）

用中文新闻问答数据集 [CRUD-RAG](https://github.com/IAAR-Shanghai/CRUD_RAG) 跑大规模评测，覆盖单篇、两篇、三篇新闻综合回答，以及原文被移出语料的应拒答题。新闻版权属于原媒体，数据只在运行时下载、转换，不进仓库。在 GitHub Actions 里手动运行 `eval-public` 工作流即可；本地运行：

```sh
curl -sSfL -o /tmp/split_merged.json \
  https://raw.githubusercontent.com/IAAR-Shanghai/CRUD_RAG/f219391e46944fc838e4fe0f77b2fcde140f75e2/data/crud_split/split_merged.json
python3 scripts/eval/crud_rag.py /tmp/split_merged.json /tmp/crud-eval   # 加 --one 4 --two 3 --three 3 --unanswerable 3 --distractors 20 先小规模试跑
MODEL_API_KEY=... EVAL_DATA_DIR=/tmp/crud-eval ./mvnw verify -pl backend/boot/rag-app -am \
  -Dtest.groups=eval -Dtest.excludedGroups=none -Dtest=EvaluationRun -Dsurefire.failIfNoSpecifiedTests=false
```

题目格式：`expectedDocuments` 列出原文所在的文档（任一篇进前 10 算召回，全部进前 10 另计“多篇原文全部召回”），`referenceAnswer` 是参考答案。带参考答案的题，回答交给评判模型对照判断为正确、部分正确或错误：

- `EVAL_JUDGE_MODEL`：评判模型，默认 `THUDM/GLM-4-9B-0414`。
- `EVAL_JUDGE_BASE_URL`：OpenAI 兼容接口地址，默认同 `MODEL_BASE_URL`；可以指向自己部署的专用评判模型。
- `EVAL_JUDGE_API_KEY`：默认用 `MODEL_API_KEY`。
- `EVAL_JUDGE_PROVIDER=none`：不评判，离线试跑时用。

评判也会判错，结论用于校准门槛前先人工抽查。换评判模型或改评判提示词后，先手动运行 `judge-probe` 工作流做评判自检：样本由 `scripts/eval/judge_probe.py` 从 CRUD-RAG 生成，回答用参考答案构造（原样、补一句原文真话、只留前一半、换成别题答案），结论事先知道，报告给出每类的准确率。

### 其他开源数据集（#159）

`eval-public` 工作流的 `dataset` 输入选择数据集，转换脚本都在 `scripts/eval/`（附转换测试：在该目录运行 `python3 -m unittest`）；数据固定到提交号并校验摘要，只在运行时下载：

| dataset | 数据 | 题数 | 许可证 | 说明 |
| --- | --- | --- | --- | --- |
| `crud` | CRUD-RAG | 500 | Apache 2.0 | 默认；应拒答题已排除语料里有相似报道的题（`crud_rag.py`） |
| `t2ranking` | [T2Ranking](https://huggingface.co/datasets/THUIR/T2Ranking) dev | 300 题、2 万段落 | Apache 2.0 | 看大规模检索的召回；下载 3.6GB，入库向量化最贵 |
| `tableeval` | [TableEval](https://github.com/wenge-research/TableEval) 简体中文 | 70 可答 + 30 拒答 | Apache 2.0 | Excel 表格问答 |
| `injection` | CRUD-RAG 新闻 + 夹带指令 | 30 | 参照 [BIPIA](https://github.com/microsoft/BIPIA)（MIT）的攻击类型 | 五类攻击各 6 题，被带偏必须为 0 |

## 真实问题 → 评测题

1. 管理员在“回答记录”页选“差评”或“答不上来”，点“导出为评测题草稿”。
2. 逐题按 `note` 的提示标注：确认 `kind`，填好 `expectedDocument` 和 `keywords`。没标完的可回答题会让评测直接失败并指出是哪一题。
3. 把标好的题合并进私有评测集的 `cases.json`，需要的资料放进 `corpus/store/`。
