# 后端镜像，在宿主机上构建（#138）：发布包里已经有编译好的 app/rag-app.jar，这里只把它放进 JRE 镜像。
# 构建上下文是发布包的目录，由 build.sh（docker compose build）调用；基础镜像由 compose.yml 按摘要传入。
ARG JRE_IMAGE
FROM ${JRE_IMAGE}
# 版本号（D-048）：写在发布包的 release.env 里，/actuator/info 显示。
ARG RAG_VERSION=dev
ENV RAG_VERSION=${RAG_VERSION}
WORKDIR /opt/rag
# 上传文件目录预先归属运行用户，命名卷首次挂载时会继承这个属主。
RUN mkdir -p /data/files && chown 10001:10001 /data/files
COPY --chown=10001:10001 app/rag-app.jar app.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
