import { defineExt } from "../runtime";
import metadata from "./metadata";

export default defineExt({ ...metadata, load: () => import("./index.vue") });
