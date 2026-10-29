import { markRaw, type Component } from "vue";
import { z } from "zod";
import type { NodeHandle } from "./nodeCore/connection";
import textNode from "./textNode/index.vue";
import imageNode from "./imageNode/index.vue";
import imageGenerationNode from "./imageGenerationNode/index.vue";
import videoNode from "./videoNode/index.vue";
import videoGenerationNode from "./videoGenerationNode/index.vue";
import audioNode from "./audioNode/index.vue";
import audioGenerationNode from "./audioGenerationNode/index.vue";
import { toolDefinitions as textTools } from "./textNode/toolDefinitions";
import { toolDefinitions as imageTools } from "./imageNode/toolDefinitions";
import { toolDefinitions as imageGenerationTools } from "./imageGenerationNode/toolDefinitions";
import { toolDefinitions as videoTools } from "./videoNode/toolDefinitions";
import { toolDefinitions as videoGenerationTools } from "./videoGenerationNode/toolDefinitions";
import { toolDefinitions as audioTools } from "./audioNode/toolDefinitions";
import { toolDefinitions as audioGenerationTools } from "./audioGenerationNode/toolDefinitions";
import { toolDefinitions as generationTools } from "./nodeCore/toolDefinitions";

export { textNode, imageNode, imageGenerationNode, videoNode, videoGenerationNode, audioNode, audioGenerationNode };
export * from "./nodeCore/runtime";
export * from "./nodeCore/nodeDocument";

export type NodeType = "textNode" | "imageNode" | "imageGenerationNode" | "videoNode" | "videoGenerationNode" | "audioNode" | "audioGenerationNode";
export type NodeToolDeclaration = { name: `node:${string}`; description: string; parameters: Record<string, unknown> };
export type NodeDefinition = {
  type: NodeType;
  label: string;
  component: Component;
  icon?: Component;
  handles: NodeHandle[];
  tools: NodeToolDeclaration[];
};

function defineNode(type: NodeType, label: string, component: Component, tools: Record<string, { name: string; description: string; parameters: z.ZodType }>): NodeDefinition {
  const options = component as Component & { icon?: Component; handles: NodeHandle[] };
  return {
    type, label, component: markRaw(component), icon: options.icon, handles: options.handles,
    tools: Object.values(tools).map(tool => ({
      name: `node:${tool.name}`,
      description: tool.description,
      parameters: z.toJSONSchema(tool.parameters, { io: "input", target: "draft-07" }),
    })),
  };
}

export const textNodeDefinition = defineNode("textNode", "文本", textNode, textTools);
export const imageNodeDefinition = defineNode("imageNode", "图片", imageNode, imageTools);
export const imageGenerationNodeDefinition = defineNode("imageGenerationNode", "图片生成", imageGenerationNode, { ...imageGenerationTools, ...generationTools });
export const videoNodeDefinition = defineNode("videoNode", "视频", videoNode, videoTools);
export const videoGenerationNodeDefinition = defineNode("videoGenerationNode", "视频生成", videoGenerationNode, { ...videoGenerationTools, ...generationTools });
export const audioNodeDefinition = defineNode("audioNode", "音频", audioNode, audioTools);
export const audioGenerationNodeDefinition = defineNode("audioGenerationNode", "音频生成", audioGenerationNode, { ...audioGenerationTools, ...generationTools });

export const nodeDefinitions = {
  textNode: textNodeDefinition,
  imageNode: imageNodeDefinition,
  imageGenerationNode: imageGenerationNodeDefinition,
  videoNode: videoNodeDefinition,
  videoGenerationNode: videoGenerationNodeDefinition,
  audioNode: audioNodeDefinition,
  audioGenerationNode: audioGenerationNodeDefinition,
};

export const nodeTypes: Record<NodeType, Component> = { textNode, imageNode, imageGenerationNode, videoNode, videoGenerationNode, audioNode, audioGenerationNode };
export const nodeToolDefinitions = Object.fromEntries(Object.values(nodeDefinitions).map(node => [node.type, node.tools])) as Record<NodeType, NodeToolDeclaration[]>;
export const defaultNodeMenu = Object.values(nodeDefinitions).map(({ type, label }) => ({ type, label }));

export default nodeDefinitions;
