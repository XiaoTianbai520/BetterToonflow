import express from "express";
import type { Request, Response, NextFunction } from "express";
import { resolve } from "node:path";
import { internalAuthentication } from "@/lib/middleware";
import { error, errorFromCause } from "@/lib/responseFormat";

export async function createApp({ dataDirectory }: { dataDirectory?: string } = {}) {
  // 路由及配置模块加载前确定共享快照目录；Runtime 不初始化或改写快照。
  process.env.TOONFLOW_DATA_DIR = resolve(dataDirectory ?? process.env.TOONFLOW_DATA_DIR ?? resolve(import.meta.dirname, "../../../data"));
  const app = express();
  app.disable("x-powered-by");
  app.use(internalAuthentication);
  app.use(express.json({ limit: "100mb" }));
  const { default: registerRoutes } = await import("@/router");
  registerRoutes(app);
  app.use((_request, response) => response.status(404).json(error("接口不存在", null, 404)));
  app.use((cause: Error, _request: Request, response: Response, next: NextFunction) => {
    if (response.headersSent) return next(cause);
    const body = errorFromCause(cause);
    response.status(body.code).json(body);
  });
  return app;
}
