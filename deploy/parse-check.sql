-- 解析核对（#134）：按文档列出入库后的解析结果，方便人工抽查；只读，由 parse-check.sh 运行。
-- 标题层级：heading_path 为“文档名 > 章节 > 小节”，取各分块里最深的一级（0 表示没有识别出标题）。
-- 表格行（估计）：一行里至少有两组“表头：值；”的行，Word / Excel 表格每行转成这个形式；只有一列的表格不计入。
-- 乱码字符：U+FFFD、私有区字符（PDF 字体映射失败时常见）和控制字符，占全部字符的百分比。
-- 空白占比：空白字符占全部字符的百分比，过高说明版面被打散。
-- 结论：失败 / 无分块 / 疑似乱码（乱码占比 ≥ 1%）/ 疑似版面散乱（空白占比 ≥ 40%）/ 未识别标题（长文档的标题层级为 0）/ 正常；
-- 结论只是提示，最终以人工看分块为准。
SELECT d.id                                                              AS 文档号,
       kb.name                                                           AS 知识库,
       d.filename                                                        AS 文档,
       d.status                                                          AS 状态,
       d.chunk_count                                                     AS 分块数,
       coalesce(s.max_level, 0)                                          AS 标题层级,
       coalesce(s.table_rows, 0)                                         AS "表格行(估计)",
       round(100.0 * coalesce(s.garbled, 0) / nullif(s.total_chars, 0), 2) AS "乱码占比(%)",
       round(100.0 * coalesce(s.blanks, 0) / nullif(s.total_chars, 0), 2)  AS "空白占比(%)",
       CASE
           WHEN d.status = 'FAILED' THEN '失败：' || coalesce(d.error, '')
           WHEN d.status = 'PARSING' THEN '解析中'
           WHEN coalesce(s.chunks, 0) = 0 THEN '无分块'
           WHEN 100.0 * s.garbled / nullif(s.total_chars, 0) >= 1 THEN '疑似乱码'
           WHEN 100.0 * s.blanks / nullif(s.total_chars, 0) >= 40 THEN '疑似版面散乱'
           WHEN s.max_level = 0 AND s.chunks >= 5 THEN '未识别标题'
           ELSE '正常'
           END                                                           AS 结论
FROM document d
         JOIN knowledge_base kb ON kb.id = d.knowledge_base_id
         LEFT JOIN LATERAL (
    SELECT count(*)                                                                       AS chunks,
           max(length(c.heading_path) - length(replace(c.heading_path, ' > ', ''))) / 3   AS max_level,
           sum((SELECT count(*) FROM regexp_matches(c.content, '(^|\n)[^：；\n]{1,30}：[^\n]*；[^：；\n]{1,30}：', 'g')))
                                                                                          AS table_rows,
           sum(length(c.content))                                                         AS total_chars,
           sum(length(c.content) - length(regexp_replace(c.content, '[\uFFFD\uE000-\uF8FF\u0001-\u0008\u000B\u000C\u000E-\u001F]', '', 'g')))
                                                                                          AS garbled,
           sum(length(c.content) - length(regexp_replace(c.content, '\s', '', 'g')))      AS blanks
    FROM chunk c
    WHERE c.document_id = d.id
    ) s ON true
ORDER BY kb.name, d.filename;
