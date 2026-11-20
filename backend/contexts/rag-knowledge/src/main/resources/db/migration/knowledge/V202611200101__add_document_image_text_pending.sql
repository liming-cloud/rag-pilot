-- 所属模块：rag-knowledge。
-- 目的：识别图片里的文字（#223、D-074）。记下文档里是否有图片还没识别（识别服务没配置或暂时不可用），
--      重新分块（Rechunker）在配置了识别服务时补做这些文档。
-- 影响：document 加一列，默认 false；已有图片的文档（image_count > 0）标为待识别，升级后只重做这些文档，
--      纯文字文档不动。兼容变更（D-067）。
-- 回滚：可以删除这一列，已识别的图片文字留在分块里。
ALTER TABLE document ADD COLUMN image_text_pending boolean NOT NULL DEFAULT false;

UPDATE document SET image_text_pending = true WHERE image_count > 0;
