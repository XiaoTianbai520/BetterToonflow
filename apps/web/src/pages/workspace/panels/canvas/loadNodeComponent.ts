import { nodeDefinitions, type NodeType } from "@/nodes";

export function getNodeIcon(type?: string) {
  const name = type?.replace(/^remote-/, "");
  return name && Object.hasOwn(nodeDefinitions, name) ? nodeDefinitions[name as NodeType].icon : undefined;
}
