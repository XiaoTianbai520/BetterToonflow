import { Router } from "express";
import { z } from "zod";
import { validateFields } from "@/lib/middleware";
import { success } from "@/lib/responseFormat";
import u from "@/utils";

const inputSchema = z.object({
  directory: z.string().min(1),
  callId: z.uuid(),
  result: z.json().optional(),
  error: z.string().min(1).max(8000).optional(),
});

export default Router().post("/", validateFields(inputSchema.shape), async (req, res) => {
  const { directory, callId, ...response } = req.body as z.infer<typeof inputSchema>;
  if (Object.hasOwn(response, "result") === Object.hasOwn(response, "error")) {
    throw Object.assign(new Error("result 与 error 必须二选一"), { status: 400 });
  }
  const cwd = await u.workspace.resolveWorkspace(req, directory);
  u.canvas.finishCanvasCall(cwd, callId, response);
  res.json(success());
});
