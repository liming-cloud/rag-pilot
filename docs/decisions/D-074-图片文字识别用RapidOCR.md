# D-074 图片文字识别：RapidOCR 容器由宿主机构建，图片文字跟在所在段落或页后面，存量只重做含图片的文档（2026-11-20，#223）

试点资料里有截图为主的操作手册和课件（#134、#218），截图里的步骤问不到。人的答复（2026-11-20）：

- **方案**：RapidOCR（PaddleOCR 中文模型的 ONNX 版），做成部署里的一个容器；**由宿主机构建，镜像不进发布包**。
- **存量**：升级后只重做含图片的文档，纯文字文档不动。
- **识别失败**：识别服务没启动或出错时跳过识别，照常入库。

## 识别服务（新增外部服务，A-21）

- `deploy/ocr.Dockerfile`：Python 基础镜像按摘要锁定，`rapidocr_api 0.2.0`、`rapidocr 3.10.0`、`onnxruntime 1.32.0`（RapidOCR 两个包是 Apache-2.0，onnxruntime 是 MIT），构建时下载识别模型进镜像，运行时不联网；以 `nobody` 运行，只在 Compose 内部网络里，不映射端口。镜像约 700 MB，标签跟 RapidOCR 版本走（`rag-pilot-ocr:rapidocr-3.10.0`），`build.sh` 本机已有时不再构建。包源可用 `RAG_PIP_INDEX_URL`、基础镜像可用 `RAG_PYTHON_IMAGE` 换成镜像源。
- 接口：`POST /ocr`（multipart 字段 `image_file`），返回按阅读顺序的文字框（文字、四角坐标、置信度）。本机实测两行中文界面截图，置信度 0.99 以上、文字无误；同一行会被切成几个框。
- 比较过的：完整 PaddleOCR 容器（中文最好，但镜像 1～2 GB、更慢）、Tesseract 装进 app 镜像（中文界面小字一般）、云 OCR（资料要发出客户环境）、不做（请客户补文字版）。

## 读取

- 客户端只有 rag-knowledge 用，按 A-06 放在 `infrastructure/parsing`（`ImageText`），用 Spring 的 `RestClient` 和已有的 Jackson，不加依赖；不经 rag-ai（A-07 管的是经 Spring AI 调用的模型服务）。地址 `rag.knowledge.ocr.url`（环境变量 `RAG_OCR_URL`，Compose 里是 `http://ocr:9003`），为空时不识别。
- Word 按段落、PowerPoint 按页、PDF 按页，图片的文字以“图片文字：”开头跟在后面，引用仍指向原文档；同一行的框合成一行，置信度低于 0.5 的框丢掉。同一份文档里同一张图（如每页的标志）只识别一次；宽或高小于 16 像素的图标、读不了的格式（如 EMF）不识别；统一转成 PNG 发送。表格单元格里的图片只计数不识别。
- 只有图片的 PowerPoint 页、扫描版 PDF 识别出文字后也能入库（以前前者不输出、后者拒收）。
- 服务连不上、超时（120 秒）或 5xx 算不可用：这份文档剩下的图不再请求，照常入库，记 `document.image_text_pending`（日志 `event=ocr_unavailable`）；服务拒收某一张图（4xx）只跳过这张（`event=ocr_image_rejected`）。没配置服务时有图片的文档也记为待识别，以后配置了再补。

## 存量与补做

- 迁移 `V202611200101` 给 `document` 加 `image_text_pending`（默认 false），已有图片的文档（`image_count > 0`）标为待识别。兼容变更。
- `Rechunker`（D-073）在配置了识别服务时，除了分块规则版本旧的，也重做待识别的文档；重读时识别服务仍不可用就停下这一轮（`event=rechunk_paused reason=ocr`），不白白再向量化，10 分钟后再看。费用只花在含图片的文档上，估算见运维手册“六、升级”。
- 体检的“疑似内容在图片里”（D-071）按分块字数判断，识别出文字后自然不再提示。

## 验证

- 单元测试：`ImageTextTest`（合行、低置信度、空结果、4xx、5xx、连不上）、`LocalDocumentReadersOcrTest`（Word、PowerPoint、PDF 和扫描页、去重、图标、不可用、没配置）；集成测试 `ImageTextTests`（截图文字入库；服务不可用时照常入库、重新分块停下，恢复后补上）。
- `verify.sh` 检查 ocr 容器就绪；CI 部署场景构建 ocr 镜像。
- 试点资料重新导入、对比识别前后的结论记 #134（只记汇总数字），由人在试点部署升级后做。
