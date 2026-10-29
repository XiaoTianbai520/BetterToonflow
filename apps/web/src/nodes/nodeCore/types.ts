export interface NodeToolInfo {
  nodeId: string;
  name: `node:${string}`;
  nodeLabel?: string;
  description: string;
  parameters: Record<string, unknown>;
}

export interface NodeToolCall {
  nodeId: string;
  name: `node:${string}`;
  args: Record<string, unknown>;
}

export interface NodeToolsContext {
  readonly tools: NodeToolInfo[];
  readonly version: number;
  list(nodeIds: string[], names?: string[]): IterableIterator<NodeToolInfo>;
  call(request: NodeToolCall, signal?: AbortSignal): Promise<unknown>;
}

export interface MediaModel {
  supportsPrice?: boolean;
  providerId: string;
  providerLabel: string;
  modelId: string;
  label: string;
  type: "image" | "video" | "audio";
  mode?: unknown;
  imageSizes?: string[];
  imageRatios?: string[];
  durationResolutionMap?: { duration: number[]; resolution: string[] }[];
  audio?: boolean | "optional";
  voices?: { title: string; voice: string }[];
}

export interface MediaReference {
  path: string;
  mimeType: string;
}

export interface MediaGenerationRequest {
  providerId: string;
  modelId: string;
  prompt: string;
  outputDirectory?: string;
  images?: MediaReference[];
  videos?: MediaReference[];
  audios?: MediaReference[];
  firstFrame?: MediaReference;
  lastFrame?: MediaReference;
  ratio?: string;
  size?: string;
  resolution?: string;
  duration?: number;
  generateAudio?: boolean;
  voice?: string;
  speed?: number;
  volume?: number;
  pitch?: number;
  language?: string;
  format?: string;
  sampleRate?: number;
  mode?: "singleImage" | "startEndRequired" | "endFrameOptional" | "startFrameOptional" | "text"
    | (`${"image" | "video" | "audio"}Reference:${number}`)[];
}
