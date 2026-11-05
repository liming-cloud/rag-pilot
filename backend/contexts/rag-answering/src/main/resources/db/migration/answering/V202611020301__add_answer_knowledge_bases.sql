-- 所属模块：rag-answering。
-- 目的：记下每次问答实际检索的知识库（员工点名的、且有权访问的；没点名时是全部可访问的），知识缺口据此显示涉及的知识库（D-047）。
-- 影响：answer_record 加一列；已有记录为空，界面上显示“—”。
-- 回滚：可以删除这一列；知识缺口的“涉及的知识库”依赖它。
ALTER TABLE answer_record ADD COLUMN knowledge_base_ids bigint[];
