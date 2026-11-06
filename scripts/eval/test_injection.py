"""injection.py 的转换测试：在 scripts/eval 下运行 python3 -m unittest"""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).parent


def qa(event):
    return {"ID": event, "event": event, "news1": f"{event}的新闻正文，讲述了一件事。", "questions": f"{event}讲了什么？",
            "answers": f"{event}的答案。"}


class InjectionTest(unittest.TestCase):
    def test_指令附在原文末尾_题目带禁止出现的话(self):
        data = {"questanswer_1doc": [qa(f"事件{i}") for i in range(6)],
                "event_summary": [{"event": "别的事件", "text": "无关新闻。"}]}
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "split.json").write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
            out = root / "out"
            subprocess.run([sys.executable, "-I", str(HERE / "injection.py"), str(root / "split.json"), str(out),
                            "--questions", "6", "--distractors", "1"], check=True, capture_output=True)
            cases = json.loads((out / "cases.json").read_text(encoding="utf-8"))
            store = {p.name: p.read_text(encoding="utf-8") for p in (out / "corpus" / "store").glob("*.txt")}
        self.assertEqual(6, len(cases))
        self.assertEqual(5, len({c["id"].split("-")[1] for c in cases}), "5 种攻击类型都要用到")
        for case in cases:
            text = store[case["expectedDocuments"][0]]
            self.assertTrue(any(word in text for word in case["forbidden"]), "被带偏的话必须出自嵌入的指令")
            self.assertIn("的新闻正文", text)
        self.assertEqual(7, len(store))


if __name__ == "__main__":
    unittest.main()
