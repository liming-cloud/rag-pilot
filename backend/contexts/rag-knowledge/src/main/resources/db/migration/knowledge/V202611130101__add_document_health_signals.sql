-- 所属模块：rag-knowledge。
-- 目的：资料体检（#218、D-070）。入库时记下判断用的信号，文档列表、知识库列表和 deploy/parse-check.sql 读同一份结果。
-- 影响：document 加六个可空列；新表 document_overlap 记“疑似重复”（一份文档 ≥ 80% 的分块正文在另一份里也有）；
--      chunk 加正文摘要索引，document 加文件摘要索引。已有文档从分块回填字数、空白、乱码、标题层级和疑似重复；
--      图片数、最大表格行要重新入库才有（为空时相应规则不判断）。兼容变更（D-067）。
-- 回滚：可以删除新表、新列和两个索引，体检结论随之没有。
ALTER TABLE document
    ADD COLUMN image_count        integer,
    ADD COLUMN largest_table_rows integer,
    ADD COLUMN total_chars        integer,
    ADD COLUMN blank_chars        integer,
    ADD COLUMN garbled_chars      integer,
    ADD COLUMN heading_depth      integer;

CREATE TABLE document_overlap
(
    tenant_id         bigint NOT NULL,
    document_id       bigint NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    other_document_id bigint NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    PRIMARY KEY (document_id, other_document_id)
);
CREATE INDEX document_overlap_other_idx ON document_overlap (other_document_id);

CREATE INDEX chunk_content_md5_idx ON chunk (tenant_id, md5(content));
CREATE INDEX document_tenant_sha256_idx ON document (tenant_id, sha256);

-- 口径与 DocumentMapper.xml 的 refreshSignals 一致：乱码是 U+FFFD、私有区和控制字符；标题层级取 heading_path 最深的一级。
UPDATE document d
SET total_chars   = s.total_chars,
    blank_chars   = s.blank_chars,
    garbled_chars = s.garbled_chars,
    heading_depth = s.heading_depth
FROM (SELECT c.document_id,
             sum(length(c.content))                                                       AS total_chars,
             sum(length(c.content) - length(regexp_replace(c.content, '\s', '', 'g')))    AS blank_chars,
             sum(length(c.content) - length(regexp_replace(c.content,
                 '[\uFFFD\uE000-\uF8FF\u0001-\u0008\u000B\u000C\u000E-\u001F]', '', 'g'))) AS garbled_chars,
             max(length(c.heading_path) - length(replace(c.heading_path, ' > ', ''))) / 3 AS heading_depth
      FROM chunk c
      GROUP BY c.document_id) s
WHERE d.id = s.document_id;

-- 口径与 DocumentMapper.xml 的 insertOverlaps 一致：按分块正文的摘要比较，去重后计数。
WITH hashes AS (SELECT DISTINCT tenant_id, document_id, md5(content) AS hash FROM chunk),
     totals AS (SELECT document_id, count(*) AS total FROM hashes GROUP BY document_id),
     shared AS (SELECT a.tenant_id, a.document_id, b.document_id AS other_document_id, count(*) AS n
                FROM hashes a
                         JOIN hashes b ON b.tenant_id = a.tenant_id AND b.hash = a.hash AND b.document_id <> a.document_id
                GROUP BY a.tenant_id, a.document_id, b.document_id)
INSERT INTO document_overlap (tenant_id, document_id, other_document_id)
SELECT s.tenant_id, s.document_id, s.other_document_id
FROM shared s
         JOIN totals t ON t.document_id = s.document_id
WHERE s.n >= 0.8 * t.total;
