# D-019 文档解析：本地 PDFBox 与 POI（2026-10-27）

客户资料主要是 PDF、Word、Excel。新增 Apache PDFBox 3.0.7（PDF）和 Apache POI 5.5.1（`poi-ooxml`，Word / Excel），在 rag-ai 里本地解析，不接 docling 等外部服务：不多一个要部署的服务，客户内网也能用，结果确定可测。不用 Tika：依赖太多，而且输出是平铺文字，会丢掉 Word 标题层级和表格表头。Word 的标题样式转成 Markdown 标题，表格每行转成“表头：值”；PDF 只按阅读顺序提取文字。已知限制：扫描件（需要 OCR）、旧版 .doc、只靠加粗放大而没用标题样式的“标题”都不识别，等拿到脱敏的真实样例再定是否加规则或换解析服务。
