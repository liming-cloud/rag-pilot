-- 所属模块：rag-answering。
-- 目的：记录回答后在后台算好问题向量存下来，知识缺口归类时直接用，不再每次把上千条问题重新向量化（#145 C6，修订 D-047）。
-- 影响：answer_record 加一列；已有记录为空，打开知识缺口时补算并写回。只用于归类，不建索引。
-- 回滚：可以删除这一列；知识缺口退回每次现算。
ALTER TABLE answer_record ADD COLUMN question_embedding real[];
