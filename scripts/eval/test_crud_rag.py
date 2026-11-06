"""crud_rag.py 的转换测试：python3 -I -m unittest discover -s scripts/eval"""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import crud_rag

HERE = Path(__file__).parent

CAR = "国家发布新能源汽车购置税减免政策，对购买新能源汽车的消费者免征购置税，政策执行到二零二七年底，鼓励绿色出行。"
CAR_REPRINT = "国家发布新能源汽车购置税减免政策，对购买新能源汽车的消费者免征购置税，政策执行到二零二七年底，鼓励绿色出行，各地陆续落实。"
EYE = "国家卫健委开展启明行动，发布防控儿童青少年近视核心知识十条，强调预防为主，推动关口前移，营造爱眼护眼环境。"
BRIDGE = "某地举办国际马拉松赛事，吸引数万名跑者参加，沿途设置补给点，医疗保障队伍全程待命，赛事顺利结束。"


def qa(event, news, question="问题？", answer="答案。"):
    return {"ID": event, "event": event, "news1": news, "questions": question, "answers": answer}


class CrudRagTest(unittest.TestCase):
    def test_重复报道的应拒答题被排除(self):
        # 事件 A 的新闻在事件 B 的条目里被重复报道：A 的题在语料里其实可答，不能当应拒答题
        other = {"event": "B", "text": CAR_REPRINT}
        self.assertTrue(crud_rag.answerable_elsewhere(qa("A", CAR), [crud_rag.grams(other["text"])]))

    def test_无关语料不影响应拒答题(self):
        self.assertFalse(crud_rag.answerable_elsewhere(qa("A", EYE), [crud_rag.grams(BRIDGE)]))

    def test_答案出现在别的文档里也算可答(self):
        item = qa("A", EYE, answer="新能源汽车购置税减免政策执行到二零二七年底")
        self.assertTrue(crud_rag.answerable_elsewhere(item, [crud_rag.grams(CAR)]))

    def test_转换后应拒答题的新闻和相似报道都不在语料里(self):
        data = {
            "questanswer_1doc": [qa("A", CAR), qa("C", EYE), qa("D", BRIDGE)],
            "questanswer_2docs": [], "questanswer_3docs": [],
            "event_summary": [{"event": "B", "text": CAR_REPRINT}],
        }
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "split_merged.json"
            source.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
            out = Path(tmp) / "out"
            subprocess.run([sys.executable, "-I", str(HERE / "crud_rag.py"), str(source), str(out), "--one", "1",
                            "--two", "0", "--three", "0", "--unanswerable", "1", "--distractors", "10"], check=True,
                           capture_output=True)
            cases = json.loads((out / "cases.json").read_text(encoding="utf-8"))
            corpus = [p.read_text(encoding="utf-8") for p in (out / "corpus" / "store").glob("*.txt")]
        refused = [c for c in cases if c["kind"] == "unanswerable"]
        self.assertEqual(1, len(refused))
        self.assertNotIn("A", refused[0]["id"], "有重复报道的 A 不能当应拒答题")
        for text in corpus:
            if refused[0]["id"].endswith("C"):
                self.assertNotEqual(EYE, text)
            if refused[0]["id"].endswith("D"):
                self.assertNotEqual(BRIDGE, text)


if __name__ == "__main__":
    unittest.main()
