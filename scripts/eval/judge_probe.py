#!/usr/bin/env python3
"""从 CRUD-RAG 生成评判自检样本：回答是用参考答案构造的，结论事先知道，用来衡量评判模型准不准。

每类若干题（固定随机种子）：
- 原样：回答就是参考答案 → 正确。
- 补充：参考答案后面再加一句原文新闻里的真话 → 正确（检验评判会不会因为“多出内容”扣分）。
- 缺半：只保留参考答案前一半的句子 → 部分正确。
- 张冠李戴：换成另一个事件的参考答案 → 错误。

新闻版权属于原媒体，样本只在运行时生成，不进仓库。只用标准库。

    python3 scripts/eval/judge_probe.py split_merged.json /tmp/judge-probe.json
"""

import argparse
import json
import random
import re
from pathlib import Path

SETS = ("questanswer_1doc", "questanswer_2docs", "questanswer_3docs")


def sentences(text):
    return [part.strip() for part in re.findall(r"[^。！？；]+[。！？；]?", text) if len(part.strip()) >= 6]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("source", type=Path, help="split_merged.json")
    parser.add_argument("output", type=Path, help="输出的 JSON 文件")
    parser.add_argument("--per-kind", type=int, default=15, help="每类题数")
    parser.add_argument("--seed", type=int, default=63)
    args = parser.parse_args()

    data = json.loads(args.source.read_text(encoding="utf-8"))
    rng = random.Random(args.seed)
    items = [item for key in SETS for item in data[key] if item["questions"] and item["answers"]]
    rng.shuffle(items)

    probes = {"原样": [], "补充": [], "缺半": [], "张冠李戴": []}
    used = set()
    for item in items:
        if all(len(group) == args.per_kind for group in probes.values()):
            break
        question, reference = item["questions"].strip(), item["answers"].strip()
        parts = sentences(reference)
        news = [s for key in sorted(item) if key.startswith("news") for s in sentences(item[key])]
        extras = [s for s in news if 15 <= len(s) <= 80 and s not in reference]
        candidates = []
        if len(probes["原样"]) < args.per_kind:
            candidates.append(("原样", reference, "CORRECT"))
        if len(probes["补充"]) < args.per_kind and extras:
            candidates.append(("补充", reference + rng.choice(extras), "CORRECT"))
        if len(probes["缺半"]) < args.per_kind and len(parts) >= 2:
            candidates.append(("缺半", "".join(parts[: len(parts) // 2]), "PARTIAL"))
        if len(probes["张冠李戴"]) < args.per_kind:
            other = rng.choice([o for o in items[:200] if o["event"] != item["event"]])
            candidates.append(("张冠李戴", other["answers"].strip(), "WRONG"))
        # 一题只用来造一类样本，避免同一道题在几类里重复出现。
        if not candidates or item["ID"] in used:
            continue
        kind, answer, expected = rng.choice(candidates)
        used.add(item["ID"])
        probes[kind].append({"id": f"{kind}-{item['ID']}", "kind": kind, "question": question,
                             "referenceAnswer": reference, "answer": answer, "expected": expected})

    result = [probe for group in probes.values() for probe in group]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print("评判自检样本 " + "，".join(f"{kind} {len(group)} 题" for kind, group in probes.items()) + f"，输出到 {args.output}")


if __name__ == "__main__":
    main()
