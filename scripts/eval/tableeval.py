#!/usr/bin/env python3
"""把 TableEval（中英文 Excel 表格问答，EMNLP 2025，仓库 wenge-research/TableEval，Apache 2.0）的简体中文题
转成评测集目录（EVAL_DATA_DIR 的格式）。数据只在运行时下载、转换，不提交进仓库。只用标准库。

    python3 scripts/eval/tableeval.py TableEval-test.jsonl TableEval-meta.jsonl /tmp/table-eval \\
      --base-url https://raw.githubusercontent.com/wenge-research/TableEval/<提交号>

抽样规则（固定随机种子）：简体中文里的“简单查询”“条件查询”（单个问题、答案一个短值）作可回答题，原表 xlsx 进语料，
作为预期文档，答案交给评判模型；“拒答”（表格里没有答案）作应拒答题。表格从 `--base-url` 下载，
`--tables-dir` 里已有的文件不再下载。
"""

import argparse
import json
import random
import urllib.parse
import urllib.request
from pathlib import Path

LANGUAGE = "简体中文"
ANSWERABLE = ("简单查询", "条件查询")
REFUSAL = "拒答"
MAX_ANSWER = 40


def final_answers(row):
    return [answer for group in row["golden_answer_list"] for q in group["问题列表"] for answer in q["最终答案"]]


def pick(rows, count, rng):
    rows = list(rows)
    rng.shuffle(rows)
    picked, tables = [], set()
    for row in rows:  # 每张表最多一题，题目覆盖更多的表
        if len(picked) == count:
            break
        if row["table_id"] not in tables:
            tables.add(row["table_id"])
            picked.append(row)
    if len(picked) < count:
        raise SystemExit(f"只抽到 {len(picked)} 题，少于要求的 {count} 题")
    return picked


def fetch(path, base_url, tables_dir):
    local = tables_dir / Path(path).name
    if not local.exists():
        url = base_url.rstrip("/") + "/" + urllib.parse.quote(path)
        with urllib.request.urlopen(url, timeout=60) as response:
            local.write_bytes(response.read())
    return local.read_bytes()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("test", type=Path, help="TableEval-test.jsonl")
    parser.add_argument("meta", type=Path, help="TableEval-meta.jsonl（含每张表的 xlsx 路径）")
    parser.add_argument("output", type=Path, help="输出目录，运行评测时设为 EVAL_DATA_DIR")
    parser.add_argument("--base-url", required=True, help="固定到提交号的 raw 地址")
    parser.add_argument("--tables-dir", type=Path, help="已下载的 xlsx 目录，默认放在输出目录旁")
    parser.add_argument("--answerable", type=int, default=70)
    parser.add_argument("--unanswerable", type=int, default=30)
    parser.add_argument("--seed", type=int, default=54)
    args = parser.parse_args()

    rng = random.Random(args.seed)
    rows = [json.loads(line) for line in args.test.read_text(encoding="utf-8").splitlines() if line]
    paths = {}
    for line in args.meta.read_text(encoding="utf-8").splitlines():
        if line:
            meta = json.loads(line)
            paths[meta["table_id"]] = meta["file_path"]
    chinese = [row for row in rows if row["context"]["table_language"] == LANGUAGE and len(row["question_list"]) == 1]
    answerable = pick((row for row in chinese if row["sub_task_name"] in ANSWERABLE
                       and len(final_answers(row)) == 1 and len(final_answers(row)[0]) <= MAX_ANSWER),
                      args.answerable, rng)
    unanswerable = pick((row for row in chinese if row["sub_task_name"] == REFUSAL), args.unanswerable, rng)

    tables_dir = args.tables_dir or args.output / "tables-cache"
    tables_dir.mkdir(parents=True, exist_ok=True)
    store = args.output / "corpus" / "store"
    store.mkdir(parents=True, exist_ok=True)
    for old in store.glob("*.xlsx"):
        old.unlink()
    cases = []
    for row, kind in [(r, "answerable") for r in answerable] + [(r, "unanswerable") for r in unanswerable]:
        name = f"表格-{row['table_id']}.xlsx"
        (store / name).write_bytes(fetch(paths[row["table_id"]], args.base_url, tables_dir))
        case = {"id": f"表格-{row['id']}", "question": row["question_list"][0], "kind": kind, "style": row["sub_task_name"]}
        if kind == "answerable":
            case.update(expectedDocuments=[name], referenceAnswer=final_answers(row)[0])
        cases.append(case)
    (args.output / "cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"语料 {len(list(store.glob('*.xlsx')))} 张表，可回答 {len(answerable)} 题，应拒答 {len(unanswerable)} 题，输出到 {args.output}")


if __name__ == "__main__":
    main()
