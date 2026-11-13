-- 解析核对（#134）与资料体检（#218）：按文档列出入库时记下的信号和体检结论，方便人工抽查；只读，由 parse-check.sh 运行。
-- 信号由入库时写进 document（图片数、最大表格行由读取时得到；字数、空白、乱码、标题层级从分块算出），管理端文档列表读同一份。
-- 结论的规则和阈值与 KnowledgeQueryMapper.xml 的 findings 一致（D-071），改一处要一起改，ParseCheckSqlTests 校验两边一致；
-- 这里按本租户全部的库找重复。多个问题用“、”连起来；结论只是提示，最终以人工看分块为准。
-- 图片数、最大表格行为空：升级前入库、还没重新上传的文档，相应规则不判断。
WITH checked AS (
    SELECT d.*,
           lower(substring(d.filename from '\.([^.]+)$')) AS extension,
           EXISTS (SELECT 1 FROM document o
                   WHERE o.tenant_id = d.tenant_id AND o.sha256 = d.sha256 AND o.id <> d.id AND o.status = 'READY') AS duplicate,
           EXISTS (SELECT 1 FROM document_overlap ov JOIN document o ON o.id = ov.other_document_id
                   WHERE ov.document_id = d.id AND o.sha256 <> d.sha256 AND o.status = 'READY')               AS near_duplicate
    FROM document d
)
SELECT d.id                                                             AS 文档号,
       kb.name                                                          AS 知识库,
       d.filename                                                       AS 文档,
       d.status                                                         AS 状态,
       d.chunk_count                                                    AS 分块数,
       d.image_count                                                    AS 图片数,
       d.largest_table_rows                                             AS 最大表格行,
       d.heading_depth                                                  AS 标题层级,
       round(100.0 * d.garbled_chars / nullif(d.total_chars, 0), 2)     AS "乱码占比(%)",
       round(100.0 * d.blank_chars / nullif(d.total_chars, 0), 2)       AS "空白占比(%)",
       CASE
           WHEN d.status = 'FAILED' THEN '失败：' || coalesce(d.error, '')
           WHEN d.status = 'PARSING' THEN '解析中'
           ELSE coalesce(nullif(concat_ws('、',
               CASE WHEN d.chunk_count = 0 THEN '没有可检索的内容' END,
               CASE WHEN d.extension IN ('docx', 'pdf', 'pptx') AND d.image_count >= 2
                         AND d.total_chars - d.blank_chars < 200 * d.image_count THEN '疑似内容在图片里' END,
               CASE WHEN d.extension = 'docx' AND d.heading_depth = 0 AND d.chunk_count >= 5 THEN 'Word 没用标题样式' END,
               CASE WHEN d.extension = 'pdf' AND d.heading_depth = 0 AND d.chunk_count >= 5 THEN 'PDF 读不出章节' END,
               CASE WHEN d.extension IN ('txt', 'md', 'markdown') AND d.heading_depth = 0 AND d.chunk_count >= 5
                    THEN '文档没有标题' END,
               CASE WHEN d.largest_table_rows >= 500 THEN '表格过大' END,
               CASE WHEN 100.0 * d.garbled_chars / nullif(d.total_chars, 0) >= 1 THEN '疑似乱码' END,
               CASE WHEN 100.0 * d.blank_chars / nullif(d.total_chars, 0) >= 40 THEN '疑似版面散乱' END,
               CASE WHEN d.duplicate THEN '与其他文档重复' END,
               CASE WHEN d.near_duplicate THEN '疑似重复' END), ''), '正常')
           END                                                          AS 结论,
       (SELECT string_agg(okb.name || '/' || o.filename, '；' ORDER BY o.id)
        FROM document o JOIN knowledge_base okb ON okb.id = o.knowledge_base_id
        WHERE o.status = 'READY' AND o.id <> d.id AND o.tenant_id = d.tenant_id
          AND (o.sha256 = d.sha256
               OR o.id IN (SELECT ov.other_document_id FROM document_overlap ov WHERE ov.document_id = d.id)))
                                                                        AS 重复的文档
FROM checked d
         JOIN knowledge_base kb ON kb.id = d.knowledge_base_id
ORDER BY kb.name, d.filename;
