# 使用 BuildKit 内置 frontend，避免离线环境拉取 docker/dockerfile:1
# 前端作为 Spring Boot 静态资源交付，只保留一个对外入口。
FROM oven/bun:1.3.14 AS webBuild
WORKDIR /app
COPY package.json bun.lock tsconfig.base.json postcss.config.ts ./
COPY patches ./patches
COPY packages ./packages
COPY apps/web ./apps/web
COPY apps/agentRuntime/package.json ./apps/agentRuntime/package.json
ARG BUN_TARBALL_REGISTRY=""
# 可选：离线/内网部署时把 bun.lock 锁定的 tarball 源改写为可达镜像源，仓库锁文件保持不变。
RUN if [ -n "$BUN_TARBALL_REGISTRY" ]; then \
      sed -i "s|https://registry.npmmirror.com|$BUN_TARBALL_REGISTRY|g" bun.lock; \
    fi && bun install --frozen-lockfile
# web 源码跨包复用 agentRuntime 的共享工具，构建阶段补齐源码（置于依赖安装之后以保留缓存层）
COPY apps/agentRuntime/src ./apps/agentRuntime/src
RUN bun run --cwd apps/web build

FROM maven:3.9-eclipse-temurin-21 AS serverBuild
WORKDIR /app
COPY apps/server/pom.xml ./pom.xml
RUN mvn -B -ntp dependency:go-offline
COPY apps/server/src ./src
COPY --from=webBuild /app/build/web ./src/main/resources/static
RUN mvn -B -ntp package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=serverBuild /app/target/server-1.0.0-SNAPSHOT.jar ./server.jar
ENV TOONFLOW_DATA_DIR=/app/data
EXPOSE 3000
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
