#!/usr/bin/env python3
"""把 T2Ranking（大规模中文段落检索，Apache 2.0）转成评测集目录（EVAL_DATA_DIR 的格式）。

数据只在运行时下载、转换，不提交进仓库。只用标准库。固定到 Hugging Face 数据集的提交
2a369a430a70979223f1b9a41b1919774d46b432，collection.tsv 约 3.6GB：

    H=https://huggingface.co/datasets/THUIR/T2Ranking/resolve/2a369a430a70979223f1b9a41b1919774d46b432/data
    curl -sSfL -O $H/collection.tsv -O $H/queries.dev.tsv -O $H/qrels.retrieval.dev.tsv
    python3 scripts/eval/t2ranking.py collection.tsv queries.dev.tsv qrels.retrieval.dev.tsv /tmp/t2-eval

抽样规则（固定随机种子）：从 dev 集抽有 1～3 个相关段落的问题，相关段落进语料，作为预期文档；其余随机段落补足到
`--passages` 段作干扰。T2Ranking 只标了相关段落、没有答案，所以参考答案取最相关段落的开头，只作评判的辅助，
这个集合主要看召回。
"""

import argparse
import json
import random
import re
from pathlib import Path

TAGS = re.compile(r"<[^>]{1,10}>")
MIN_LENGTH = 30


def clean(text):
    return TAGS.sub("", text).strip()


def read_queries(path):
    queries = {}
    for line in path.read_text(encoding="utf-8").splitlines()[1:]:
        qid, _, text = line.partition("\t")
        if text:
            queries[qid] = text.strip()
    return queries


def read_qrels(path):
    qrels = {}
    for line in path.read_text(encoding="utf-8").splitlines()[1:]:
        qid, _, pid = line.partition("\t")
        if pid:
            qrels.setdefault(qid, []).append(pid.strip())
    return qrels


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("collection", type=Path, help="collection.tsv（pid、passage）")
    parser.add_argument("queries", type=Path, help="queries.dev.tsv")
    parser.add_argument("qrels", type=Path, help="qrels.retrieval.dev.tsv")
    parser.add_argument("output", type=Path, help="输出目录，运行评测时设为 EVAL_DATA_DIR")
    parser.add_argument("--questions", type=int, default=300, help="问题数")
    parser.add_argument("--passages", type=int, default=20000, help="语料段落数（含相关段落）")
    parser.add_argument("--max-pid", type=int, default=2303642, help="collection.tsv 里最大的 pid，用来抽干扰段落")
    parser.add_argument("--seed", type=int, default=54)
    args = parser.parse_args()

    rng = random.Random(args.seed)
    queries = read_queries(args.queries)
    qrels = read_qrels(args.qrels)
    candidates = sorted(qid for qid, pids in qrels.items() if qid in queries and 1 <= len(pids) <= 3)
    rng.shuffle(candidates)
    # 多取一些候选：相关段落太短或被清洗掉的问题要换掉
    chosen = candidates[:args.questions * 2]
    needed = {pid for qid in chosen for pid in qrels[qid]}
    extra = rng.sample(range(args.max_pid + 1), min(args.passages * 2, args.max_pid + 1))
    wanted = needed | {str(pid) for pid in extra}

    passages = {}
    first_pid_of = {}  # T2Ranking 里有内容完全相同的段落，入库会按内容判重，所以相同文本只留第一个 pid
    with args.collection.open(encoding="utf-8") as source:
        next(source)
        for line in source:
            pid, _, text = line.rstrip("\n").partition("\t")
            if pid in wanted:
                text = clean(text)
                if len(text) >= MIN_LENGTH:
                    first_pid_of.setdefault(text, pid)
                    passages[pid] = text

    cases = []
    relevant = set()
    for qid in chosen:
        pids = [pid for pid in qrels[qid] if pid in passages]
        if len(pids) != len(qrels[qid]) or len(cases) == args.questions:
            continue
        pids = list(dict.fromkeys(first_pid_of[passages[pid]] for pid in pids))
        relevant.update(pids)
        cases.append({"id": f"T2-{qid}", "question": queries[qid], "kind": "answerable", "style": "段落检索",
                      "expectedDocuments": [f"段落-{pid}.txt" for pid in pids],
                      "referenceAnswer": passages[pids[0]][:300]})
    if len(cases) < args.questions:
        raise SystemExit(f"只抽到 {len(cases)} 题，少于要求的 {args.questions} 题")

    corpus = {pid: passages[pid] for pid in relevant}
    for pid, text in passages.items():
        if len(corpus) >= args.passages:
            break
        if first_pid_of[text] == pid:
            corpus.setdefault(pid, text)

    store = args.output / "corpus" / "store"
    store.mkdir(parents=True, exist_ok=True)
    for old in store.glob("*.txt"):
        old.unlink()
    for pid, text in corpus.items():
        (store / f"段落-{pid}.txt").write_text(text, encoding="utf-8")
    (args.output / "cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"语料 {len(corpus)} 段，{len(cases)} 题，输出到 {args.output}")


if __name__ == "__main__":
    main()
