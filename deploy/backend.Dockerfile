# 构建阶段：用 Maven Wrapper 只构建 rag-app 及其依赖模块。
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY backend backend
RUN ./mvnw -B -ntp -pl backend/rag-app -am package -DskipTests

# 运行阶段：只保留 JRE 和一个 jar，以非 root 用户运行。
FROM eclipse-temurin:25-jre
WORKDIR /opt/rag
COPY --from=builder --chown=10001:10001 /workspace/backend/rag-app/target/rag-app.jar app.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
