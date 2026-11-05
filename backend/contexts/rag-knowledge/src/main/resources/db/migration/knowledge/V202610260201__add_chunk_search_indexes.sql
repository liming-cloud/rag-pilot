-- 所属模块：rag-knowledge。
-- 目的：混合检索。向量列（维度与 EmbeddingClient.DIMENSIONS 一致）加 HNSW 余弦索引；
--       正文加 BM25 索引（jieba 中文分词），租户和知识库列一起进索引，便于过滤下推。
-- 影响：已有分块的向量为空，需要重新上传对应文档才能参与向量检索。
-- 回滚：删除两个索引和 embedding 列即可。
ALTER TABLE chunk ADD COLUMN embedding vector(1024);

CREATE INDEX chunk_embedding_hnsw_idx ON chunk USING hnsw (embedding vector_cosine_ops);

CREATE INDEX chunk_content_bm25_idx ON chunk
    USING bm25 (id, (content::pdb.jieba), tenant_id, knowledge_base_id)
    WITH (key_field = 'id');
