#!/usr/bin/env python3
"""把 CRUD-RAG 的问答数据转成评测集目录（EVAL_DATA_DIR 的格式）。

CRUD-RAG（https://github.com/IAAR-Shanghai/CRUD_RAG，README 标注 Apache 2.0）的语料是中文新闻，
版权属于原媒体，所以只在运行时下载、转换，不提交进仓库。只用标准库。

    curl -sSfL -o split_merged.json \\
      https://raw.githubusercontent.com/IAAR-Shanghai/CRUD_RAG/main/data/crud_split/split_merged.json
    python3 scripts/eval/crud_rag.py split_merged.json /tmp/crud-eval

抽样规则（固定随机种子，同一参数每次抽到同样的题）：
- 可回答题：从 questanswer_1doc / 2docs / 3docs 各抽若干题，每个新闻事件最多一题；题目给出的原文新闻进语料，
  作为预期文档，参考答案交给评判模型。
- 应拒答题：另取若干事件，把这些事件的全部新闻都排除在语料之外，再用它们的单文档问题提问。
- 干扰文档：event_summary 里其余事件的新闻全文，最多若干篇。
"""

import argparse
import hashlib
import json
import random
from pathlib import Path

QA_SETS = (("questanswer_1doc", "单文档"), ("questanswer_2docs", "双文档"), ("questanswer_3docs", "三文档"))


def news_fields(item):
    return [item[key] for key in sorted(item) if key.startswith("news") and item[key]]


def document_name(text):
    return "新闻-" + hashlib.sha1(text.encode("utf-8")).hexdigest()[:12] + ".txt"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("source", type=Path, help="split_merged.json")
    parser.add_argument("output", type=Path, help="输出目录，运行评测时设为 EVAL_DATA_DIR")
    parser.add_argument("--one", type=int, default=200, help="单文档题数")
    parser.add_argument("--two", type=int, default=100, help="双文档题数")
    parser.add_argument("--three", type=int, default=100, help="三文档题数")
    parser.add_argument("--unanswerable", type=int, default=100, help="应拒答题数")
    parser.add_argument("--distractors", type=int, default=2000, help="干扰文档篇数上限")
    parser.add_argument("--seed", type=int, default=54)
    args = parser.parse_args()

    data = json.loads(args.source.read_text(encoding="utf-8"))
    rng = random.Random(args.seed)

    # 同一事件的新闻可能出现在多个问答集和 event_summary 里：按事件整体划分，避免拒答题的答案从别处漏进语料。
    unanswerable_pool = list(data["questanswer_1doc"])
    rng.shuffle(unanswerable_pool)
    excluded_events = set()
    unanswerable = []
    for item in unanswerable_pool:
        if len(unanswerable) == args.unanswerable:
            break
        if item["event"] not in excluded_events:
            excluded_events.add(item["event"])
            unanswerable.append(item)

    used_events = set(excluded_events)
    answerable = []
    for (key, style), count in zip(QA_SETS, (args.one, args.two, args.three)):
        pool = list(data[key])
        rng.shuffle(pool)
        picked = 0
        for item in pool:
            if picked == count:
                break
            if item["event"] in used_events or not item["questions"] or not item["answers"]:
                continue
            used_events.add(item["event"])
            answerable.append((item, style))
            picked += 1
        if picked < count:
            raise SystemExit(f"{key} 只抽到 {picked} 题，少于要求的 {count} 题")

    corpus = {}
    cases = []
    for item, style in answerable:
        names = []
        for text in news_fields(item):
            name = document_name(text)
            corpus[name] = text
            if name not in names:
                names.append(name)
        cases.append({"id": f"{style}-{item['ID']}", "question": item["questions"].strip(), "kind": "answerable",
                      "expectedDocuments": names, "referenceAnswer": item["answers"].strip(), "style": style})
    for item in unanswerable:
        cases.append({"id": f"无原文-{item['ID']}", "question": item["questions"].strip(), "kind": "unanswerable",
                      "style": "无原文"})

    excluded_texts = {text for key, _ in QA_SETS for item in data[key] if item["event"] in excluded_events
                      for text in news_fields(item)}
    distractors = [item for item in data["event_summary"] if item["event"] not in used_events and item["text"]
                   and item["text"] not in excluded_texts]
    rng.shuffle(distractors)
    for item in distractors[:args.distractors]:
        corpus.setdefault(document_name(item["text"]), item["text"])

    store = args.output / "corpus" / "store"
    store.mkdir(parents=True, exist_ok=True)
    for old in store.glob("*.txt"):
        old.unlink()
    for name, text in corpus.items():
        (store / name).write_text(text, encoding="utf-8")
    (args.output / "cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"语料 {len(corpus)} 篇，可回答 {len(answerable)} 题，应拒答 {len(unanswerable)} 题，输出到 {args.output}")


if __name__ == "__main__":
    main()
