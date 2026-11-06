"""t2ranking.py 的转换测试：在 scripts/eval 下运行 python3 -m unittest"""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).parent


def passage(i):
    return f"第{i}号段落：门店冷柜每天需要检查温度并记录，发现异常立即通知店长处理，编号{i}。"


class T2RankingTest(unittest.TestCase):
    def test_相关段落进语料并作为预期文档_标签被清掉(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            lines = ["pid\ttext"] + [f"{i}\t{passage(i)}" for i in range(40)]
            lines[3] = "2\t<br><img>" + passage(2)
            (root / "collection.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8")
            (root / "queries.tsv").write_text("qid\ttext\n0\t冷柜怎么检查\n1\t异常怎么办\n", encoding="utf-8")
            (root / "qrels.tsv").write_text("qid\tpid\n0\t2\n1\t7\n1\t9\n", encoding="utf-8")
            out = root / "out"
            subprocess.run([sys.executable, "-I", str(HERE / "t2ranking.py"), str(root / "collection.tsv"),
                            str(root / "queries.tsv"), str(root / "qrels.tsv"), str(out), "--questions", "2",
                            "--passages", "10", "--max-pid", "39"], check=True, capture_output=True)
            cases = {c["id"]: c for c in json.loads((out / "cases.json").read_text(encoding="utf-8"))}
            names = {p.name for p in (out / "corpus" / "store").glob("*.txt")}
            self.assertEqual({"T2-0", "T2-1"}, set(cases))
            self.assertEqual(["段落-7.txt", "段落-9.txt"], cases["T2-1"]["expectedDocuments"])
            self.assertTrue({"段落-2.txt", "段落-7.txt", "段落-9.txt"} <= names)
            self.assertEqual(10, len(names))
            self.assertEqual(passage(2), (out / "corpus" / "store" / "段落-2.txt").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
