# syntax=docker/dockerfile:1
# 多阶段构建：Go worker（静态单二进制）+ Java 打包 + JRE 运行层。
# 测试不在这里跑（CI 已兜底），镜像构建只管出产物。

FROM golang:1-alpine AS go-builder
WORKDIR /src/go-worker
COPY go-worker/go.mod ./
COPY go-worker/*.go ./
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o /out/recon-worker .

FROM maven:3.9-eclipse-temurin-17 AS java-builder
WORKDIR /src
COPY pom.xml mvnw ./
COPY .mvn ./.mvn
COPY src ./src
RUN chmod +x mvnw && ./mvnw -B --no-transfer-progress -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=go-builder /out/recon-worker /usr/local/bin/recon-worker
COPY --from=java-builder /src/target/recon-lens-0.1.0.jar app.jar
COPY src/main/resources/rules/ /app/rules/

# 规则目录与 worker 路径在容器内是确定的，直接钉死
ENV RECON_RULES=/app/rules/
ENV RECON_EXECUTION_WORKERPATH=/usr/local/bin/recon-worker

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
