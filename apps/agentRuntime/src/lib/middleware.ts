import type { Request, Response, NextFunction } from "express";
import { z } from "zod";
import { error } from "./responseFormat";
import { t, translateMessage, validationOptions } from "./messages";

export function internalAuthentication(req: Request, res: Response, next: NextFunction) {
  const token = process.env.TOONFLOW_INTERNAL_TOKEN;
  if (!token || req.get("X-Toonflow-Internal-Token") !== token) {
    res.status(403).json(error("禁止访问", null, 403));
    return;
  }
  next();
}

export function validateFields(
  shape: Record<string, z.ZodType>,
  source: "body" | "query" | "params" = "body", // 默认校验 body
) {
  const schema = z.object(shape);

  return (req: Request, res: Response, next: NextFunction) => {
    const data = req[source];
    const parseResult = schema.safeParse(data, validationOptions());
    if (!parseResult.success) {
      const errors = parseResult.error.issues.map((issue) => t`字段 ${issue.path.join(".")} ${translateMessage(issue.message)}`);
      console.error(errors);
      return res.status(400).json(error("参数错误", errors));
    }
    next();
  };
}
