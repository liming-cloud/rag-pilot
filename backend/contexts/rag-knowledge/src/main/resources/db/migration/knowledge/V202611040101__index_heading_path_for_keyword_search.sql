-- 所属模块：rag-knowledge。
-- 目的：关键词检索（BM25）也查分块的章节路径（以文件名开头，如“冷柜管理 > 除霜”），正文里没有、只在标题里的词也能找到
--       这一块（#145 B4，参考 Anthropic Contextual Retrieval：给分块补上下文能减少检索失败）。向量化原本就带章节路径。
-- 影响：重建 BM25 索引，分块多时需要一段时间；只重建索引，不改数据。
-- 回滚：按 V202610260201 的定义重建索引（只含正文）。
DROP INDEX IF EXISTS chunk_content_bm25_idx;
CREATE INDEX chunk_content_bm25_idx ON chunk
    USING bm25 (id, (content::pdb.jieba), (heading_path::pdb.jieba), tenant_id, knowledge_base_id)
    WITH (key_field = 'id');
