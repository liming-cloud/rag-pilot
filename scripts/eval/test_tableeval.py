"""tableeval.py 的转换测试：在 scripts/eval 下运行 python3 -m unittest"""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).parent


def row(row_id, table_id, sub_task, question, answer, language="简体中文"):
    return {"id": row_id, "table_id": table_id, "sub_task_name": sub_task, "question_list": [question],
            "golden_answer_list": [{"问题列表": [{"问题": question, "最终答案": [answer]}]}],
            "context": {"table_language": language}}


class TableEvalTest(unittest.TestCase):
    def test_只取简体中文_可回答题带原表和答案_拒答题没有预期文档(self):
        rows = [row("1", "10", "简单查询", "2024年营收是多少？", "12.5亿元"),
                row("2", "11", "条件查询", "华东区销量是多少？", "300"),
                row("3", "12", "拒答", "投机类衍生品金额？", "表格中未提供回答问题所需关键信息，因此无法回答。"),
                row("4", "13", "简单查询", "How much?", "5", language="英文"),
                row("5", "14", "简单查询", "營收是多少？", "3", language="繁体中文")]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "test.jsonl").write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in rows), encoding="utf-8")
            (root / "meta.jsonl").write_text("\n".join(json.dumps({"table_id": t, "file_path": f"data/tables/{t}_表.xlsx"})
                                                       for t in ("10", "11", "12", "13", "14")), encoding="utf-8")
            tables = root / "tables"
            tables.mkdir()
            for t in ("10", "11", "12"):
                (tables / f"{t}_表.xlsx").write_bytes(b"xlsx-" + t.encode())
            out = root / "out"
            subprocess.run([sys.executable, "-I", str(HERE / "tableeval.py"), str(root / "test.jsonl"),
                            str(root / "meta.jsonl"), str(out), "--base-url", "http://invalid.example",
                            "--tables-dir", str(tables), "--answerable", "2", "--unanswerable", "1"],
                           check=True, capture_output=True)
            cases = {c["id"]: c for c in json.loads((out / "cases.json").read_text(encoding="utf-8"))}
            store = {p.name: p.read_bytes() for p in (out / "corpus" / "store").glob("*.xlsx")}
        self.assertEqual({"表格-1", "表格-2", "表格-3"}, set(cases))
        self.assertEqual(["表格-10.xlsx"], cases["表格-1"]["expectedDocuments"])
        self.assertEqual("12.5亿元", cases["表格-1"]["referenceAnswer"])
        self.assertEqual("unanswerable", cases["表格-3"]["kind"])
        self.assertNotIn("expectedDocuments", cases["表格-3"])
        self.assertEqual({"表格-10.xlsx", "表格-11.xlsx", "表格-12.xlsx"}, set(store))
        self.assertEqual(b"xlsx-10", store["表格-10.xlsx"])


if __name__ == "__main__":
    unittest.main()
