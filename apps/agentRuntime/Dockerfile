# syntax=docker/dockerfile:1
FROM oven/bun:1.3.14
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl socat \
    && rm -rf /var/lib/apt/lists/*
COPY package.json bun.lock tsconfig.base.json ./
COPY patches ./patches
COPY packages ./packages
COPY apps/web/package.json ./apps/web/package.json
COPY apps/agentRuntime ./apps/agentRuntime
RUN bun install --frozen-lockfile
ENV NODE_ENV=production
ENV TOONFLOW_DATA_DIR=/app/data
ENV TOONFLOW_AGENT_RUNTIME_PORT=3001
WORKDIR /app/apps/agentRuntime
EXPOSE 3001
# 保留源码的回环监听约束，仅在容器网卡上转发 3001；不发布宿主机端口。
# 3000 回环转发兼容 Runtime 个性化文档保存调用，数据库仍由 Java 唯一写入。
# 任一子进程退出即停止容器，Compose init 负责回收子进程。
CMD ["bash", "-ec", "trap 'kill $(jobs -p) 2>/dev/null || true' EXIT; trap 'exit 0' TERM INT; socat TCP-LISTEN:3001,bind=\"$(hostname -i)\",fork,reuseaddr TCP:127.0.0.1:3001 & socat TCP-LISTEN:3000,bind=127.0.0.1,fork,reuseaddr TCP:server:3000 & bun src/index.ts & wait -n; exit 1"]
