import { Router } from "express";
import { z } from "zod";
import { validateFields } from "@/lib/middleware";
import u from "@/utils";

export default Router().get("/", validateFields({
  accountIds: z.string().max(6499).regex(/^[a-f0-9]{64}(,[a-f0-9]{64}){0,99}$/),
}, "query"), (req, res) => {
  u.mediaBilling.connectBalanceEvents((req.query.accountIds as string).split(","), res);
});
