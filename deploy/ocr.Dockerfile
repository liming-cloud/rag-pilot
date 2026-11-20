# 图片文字识别（#223，D-074）：RapidOCR（PaddleOCR 中文模型的 ONNX 版）的 HTTP 服务，app 入库时把文档里的图片发给它。
# 由 build.sh 在宿主机上构建，镜像不进发布包；Python 基础镜像按摘要锁定，Python 包锁定版本。
# 模型在构建时下载进镜像，运行时不联网。用镜像源时在 .env 里设 RAG_PYTHON_IMAGE、RAG_PIP_INDEX_URL。
ARG PYTHON_IMAGE
FROM ${PYTHON_IMAGE}

ARG PIP_INDEX_URL=https://pypi.org/simple
RUN pip install --no-cache-dir --index-url "$PIP_INDEX_URL" \
        rapidocr_api==0.2.0 rapidocr==3.10.0 onnxruntime==1.32.0 \
    && python -c "from rapidocr import RapidOCR; RapidOCR()"

USER nobody
EXPOSE 9003
CMD ["rapidocr_api", "-ip", "0.0.0.0", "-p", "9003", "-workers", "1"]
