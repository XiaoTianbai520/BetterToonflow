/**
 * Breeze TTS 2 · 本地 ComfyUI 供应商（v2）
 *
 * v1（2026-11-03）走的是 127.0.0.1:7860 上的独立 FastAPI 边车，边车与其 7.3 GB 权重已于
 * 2026-11-06 删除，v1 的以下约定随之作废，勿再依赖：
 *   - baseUrl 默认 7860、GET /health 预检、multipart POST /v1/audio/speech、409 排队重试
 *   - 返回 16 bit PCM + 前端拼 WAV、applyGain 音量增益、supportedRates 8000/16000/24000/48000
 *   - wav/mp3/pcm 输出与宿主 audio.convert 转码、supportedLanguages、emotionHints
 *   - 两个模型（design/clone），voice 取值是 gentleFemale 这类预置音色 ID
 *
 * v2 直接驱动 ComfyUI 的 ComfyUI-Breeze-TTS-2 节点包：模型由 ComfyUI 统一管理，原生输出
 * 24 kHz，SaveAudioAdvanced 直接给出 FLAC/MP3/Opus，不再需要独立服务与转码链路。
 */

const rules = [
  {
    type: "input",
    field: "baseUrl" as const,
    title: "ComfyUI 地址",
    value: "http://127.0.0.1:8188",
    props: { placeholder: "http://127.0.0.1:8188" },
  },
] as const;

type Rules = typeof rules;
type Context = ProviderContext<ProviderConfig<Rules>>;

function comfyBase(config: { baseUrl?: unknown }) {
  const value = String(config.baseUrl ?? "").trim() || "http://127.0.0.1:8188";
  return value.replace(/\/+$/, "");
}

const checkpoint = "int8 hybrid (recommended)";
const whisperModel = "whisper-large-v3-turbo";
/** 保存节点固定用 9 号，便于从 history.outputs["9"] 取结果。 */
const saveNode = "9";
const nativeRate = 24000;
const maxTextChars = 4000;
const maxMediaBytes = 100 * 1024 * 1024;

const audioModels = ["breezeTtsDesign", "breezeTtsClone", "breezeTtsDirection"] as const;

function pause(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    signal.throwIfAborted();
    const done = () => {
      signal.removeEventListener("abort", onAbort);
      resolve();
    };
    const timer = setTimeout(done, ms);
    const onAbort = () => {
      clearTimeout(timer);
      signal.removeEventListener("abort", onAbort);
      reject(signal.reason);
    };
    signal.addEventListener("abort", onAbort, { once: true });
  });
}

async function jsonRequest(
  context: Context,
  base: string,
  path: string,
  signal: AbortSignal,
  init: RequestInit = {},
): Promise<any> {
  const response = await context.tool.fetch(`${base}${path}`, {
    ...init,
    signal: AbortSignal.any([signal, AbortSignal.timeout(60000)]),
  });
  if (!response.ok)
    throw new Error(`ComfyUI ${path.split("?")[0]}：HTTP ${response.status} ${context.tool.errorMessage(await response.text())}`);
  return await response.json();
}

function detectAudio(bytes: Uint8Array) {
  const head = Buffer.from(bytes.subarray(0, 12));
  if (head.subarray(0, 4).toString("ascii") === "RIFF" && head.subarray(8, 12).toString("ascii") === "WAVE")
    return { ext: "wav", mime: "audio/wav" };
  if (head.subarray(0, 4).toString("ascii") === "fLaC") return { ext: "flac", mime: "audio/flac" };
  if (head.subarray(0, 4).toString("ascii") === "OggS") return { ext: "ogg", mime: "audio/ogg" };
  if (head.subarray(4, 8).toString("ascii") === "ftyp") return { ext: "m4a", mime: "audio/mp4" };
  if (head.subarray(0, 3).toString("ascii") === "ID3" || (head[0] === 0xff && (head[1] & 0xe0) === 0xe0))
    return { ext: "mp3", mime: "audio/mpeg" };
  return null;
}

/** ComfyUI 的 /upload/image 同时接受音频，落到 input 目录后供 LoadAudio 读取。 */
async function uploadAudio(context: Context, base: string, input: MediaInput, signal: AbortSignal) {
  let bytes: Uint8Array;
  if (input.type === "url") {
    const url = new URL(input.url);
    if (!["http:", "https:"].includes(url.protocol)) throw new Error("参考音频 URL 须使用 HTTP(S)");
    const response = await context.tool.fetch(url, { signal: AbortSignal.any([signal, AbortSignal.timeout(300000)]) });
    if (!response.ok) throw new Error(`读取参考音频失败：HTTP ${response.status}`);
    if (Number(response.headers.get("content-length")) > maxMediaBytes) throw new Error("参考音频超过 100 MB");
    bytes = new Uint8Array(await response.arrayBuffer());
  } else if (input.type === "base64") {
    bytes = Buffer.from(input.data.replace(/^data:[^;,]+;base64,/, ""), "base64");
  } else bytes = input.data;
  if (!bytes?.length || bytes.length > maxMediaBytes) throw new Error("参考音频为空或超过 100 MB");
  const probe = detectAudio(bytes);
  if (!probe) throw new Error("参考音频须为 WAV、MP3、FLAC、OGG 或 M4A");
  const filename = `breezeRef${Date.now()}${Math.floor(Math.random() * 1e6)}.${probe.ext}`;
  const boundary = `breezeTts${Date.now()}`;
  const body = Buffer.concat([
    Buffer.from(
      `--${boundary}\r\nContent-Disposition: form-data; name="image"; filename="${filename}"\r\nContent-Type: ${probe.mime}\r\n\r\n`,
    ),
    Buffer.from(bytes),
    Buffer.from(`\r\n--${boundary}\r\nContent-Disposition: form-data; name="type"\r\n\r\ninput\r\n--${boundary}--\r\n`),
  ]);
  const result = await jsonRequest(context, base, "/upload/image", signal, {
    method: "POST",
    headers: { "Content-Type": `multipart/form-data; boundary=${boundary}` },
    body,
  });
  if (!result.name || result.type !== "input") throw new Error("ComfyUI 未返回有效的上传音频");
  return result.subfolder ? `${result.subfolder}/${result.name}` : result.name;
}

/** 12.5 帧/秒；按中文约 4 字/秒宽松估算，只作为上限，模型遇到 EOS 会提前停。 */
function frameBudget(text: string) {
  return Math.max(400, Math.min(3000, Math.ceil(text.length * 3)));
}

type Graph = Record<string, { class_type: string; inputs: Record<string, any>; _meta?: { title: string } }>;

function baseGraph(): Graph {
  return {
    "1": {
      class_type: "BreezeTTS2LoadModel",
      _meta: { title: "Breeze TTS 2 INT8" },
      inputs: {
        model: checkpoint,
        dtype: "auto",
        device: "auto",
        attention: "auto",
        decode_mode: "cuda_graphs",
        download_if_missing: false,
      },
    },
  };
}

function addSave(graph: Graph, from: string, format: string, prefix: string) {
  const inputs: Record<string, any> = { audio: [from, 0], filename_prefix: prefix, format };
  // SaveAudioAdvanced 的动态下拉会把子参数展开成 "format.quality" 这个键。
  if (format === "mp3") inputs["format.quality"] = "V0";
  if (format === "opus") inputs["format.quality"] = "128k";
  graph[saveNode] = { class_type: "SaveAudioAdvanced", _meta: { title: "保存音频" }, inputs };
}

function sampler(maxNewTokens: number, seed: number) {
  return {
    cfg_scale: 4,
    max_new_tokens: maxNewTokens,
    temperature: 0.9,
    top_k: 50,
    top_p: 1,
    repetition_penalty: 1.1,
    depth_temperature: 0.9,
    depth_top_k: 50,
    depth_top_p: 1,
    seed,
  };
}

function whisperNode(audio: [string, number]) {
  return {
    class_type: "BreezeWhisperTranscribe",
    _meta: { title: "Whisper 转写" },
    inputs: {
      audio,
      model: whisperModel,
      dtype: "auto",
      language: "auto",
      task: "transcribe",
      chunk_length_s: 30,
      download_if_missing: false,
    },
  };
}

function designGraph(text: string, instruction: string, seed: number) {
  const graph = baseGraph();
  graph["2"] = {
    class_type: "BreezeTTS2VoiceDesign",
    _meta: { title: "声音设计" },
    inputs: { breeze_model: ["1", 0], text, instruction, ...sampler(frameBudget(text), seed) },
  };
  return graph;
}

function cloneGraph(text: string, file: string, transcript: string | null, seed: number) {
  const graph = baseGraph();
  graph["2"] = { class_type: "LoadAudio", _meta: { title: "参考音频" }, inputs: { audio: file } };
  let textRef: string | [string, number] = transcript ?? "";
  if (!transcript) {
    graph["3"] = whisperNode(["2", 0]);
    textRef = ["3", 1];
  }
  graph["4"] = {
    class_type: "BreezeTTS2VoiceClone",
    _meta: { title: "音色克隆" },
    inputs: {
      breeze_model: ["1", 0],
      text,
      reference_audio: ["2", 0],
      reference_text: textRef,
      ...sampler(frameBudget(text), seed),
      cfg_scale: 1,
    },
  };
  return graph;
}

function directionGraph(text: string, file: string, transcript: string | null, instruction: string, seed: number) {
  const graph = baseGraph();
  graph["2"] = { class_type: "LoadAudio", _meta: { title: "参考音频" }, inputs: { audio: file } };
  let textRef: string | [string, number] = transcript ?? "";
  if (!transcript) {
    graph["3"] = whisperNode(["2", 0]);
    textRef = ["3", 1];
  }
  graph["4"] = {
    class_type: "BreezeTTS2VoiceDirection",
    _meta: { title: "声音导演" },
    inputs: {
      breeze_model: ["1", 0],
      text,
      reference_audio: ["2", 0],
      reference_text: textRef,
      instruction,
      stitch_reference: "none",
      ...sampler(frameBudget(text), seed),
    },
  };
  return graph;
}

async function nodeInfo(context: Context, base: string, signal: AbortSignal, node: string) {
  const info = await jsonRequest(context, base, `/object_info/${encodeURIComponent(node)}`, signal);
  return info?.[node] as { input?: { required?: Record<string, unknown[]> } } | undefined;
}

/** 只看单个节点，避免整份 /object_info（本机自定义节点多，体积可达数 MB）。 */
async function preflight(context: Context, base: string, signal: AbortSignal, needWhisper: boolean) {
  for (const type of ["BreezeTTS2LoadModel", "SaveAudioAdvanced"]) {
    if (!(await nodeInfo(context, base, signal, type)))
      throw new Error(`ComfyUI 缺少节点：${type}（请安装 ComfyUI-Breeze-TTS-2）`);
  }
  const loader = await nodeInfo(context, base, signal, "BreezeTTS2LoadModel");
  const options = loader?.input?.required?.model?.[0];
  if (!Array.isArray(options) || !options.includes(checkpoint))
    throw new Error(`ComfyUI 中找不到 Breeze TTS 2 权重：${checkpoint}（模型可能未下载）`);
  if (!needWhisper) return;
  const whisper = await nodeInfo(context, base, signal, "BreezeWhisperTranscribe");
  if (!whisper) throw new Error("ComfyUI 缺少节点：BreezeWhisperTranscribe（请更新 ComfyUI-Breeze-TTS-2）");
  const models = whisper.input?.required?.model?.[0];
  if (!Array.isArray(models) || !models.includes(whisperModel))
    throw new Error(`ComfyUI 中找不到 Whisper 模型 ${whisperModel}，请先下载到 models/audio_encoders`);
}

export default {
  id: "breezeTtsLocal",
  label: "本地 Breeze TTS 2 · RTX3060",
  version: "2.0.0",
  readme: `连接本机 ComfyUI（默认 127.0.0.1:8188），使用 ComfyUI-Breeze-TTS-2 节点包与 int8-hybrid 权重；不需要 API Key，也不需要额外的 TTS 服务。

三个入口：
- breezeTtsDesign 声音设计：纯文字描述造音色，音色下拉就是声音描述。
- breezeTtsClone 音色克隆：需要 1 段参考音频，未提供逐字稿时自动用 Whisper 转写。
- breezeTtsDirection 声音导演：参考音频 + 音色下拉里的表演指令（耳语、激动、悲伤……）。

原生输出 24 kHz。采样率只支持 24000；输出格式选 MP3 得到 MP3、选 Opus 得到 Opus，选 WAV/PCM/FLAC 时统一返回 FLAC（Breeze 原生无损输出，不做重编码）。语速、音量、音调、情绪参数不支持，传入非默认值会明确报错；语种由文本自动判断，language 参数被忽略。

单次等待上限 1 小时，取消会请求 ComfyUI 中止该任务。模型由 ComfyUI 统一管理，与图像/视频工作流切换时自动卸载；克隆与导演模式额外需要 Whisper（约 1.6 GB，装在 models/audio_encoders）。`,
  rules,
  models: [
    {
      id: "breezeTtsDesign",
      label: "Breeze 声音设计 · TEXT_TO_SPEECH",
      type: "audio",
      voices: [
        { title: "温柔女声", voice: "一位温柔自信的年轻女性，声音清晰，语气亲切，表达轻快而富有感染力。" },
        { title: "沉稳男声", voice: "一位沉稳的中年男性，中低音，语速平缓，语气可信。" },
        { title: "少年活力", voice: "一位充满活力的少年，音调明亮，语速偏快，情绪饱满。" },
        { title: "磁性旁白", voice: "一位低音磁性的纪录片旁白，语速从容，句尾略微下沉。" },
        { title: "甜美女声", voice: "一位甜美的年轻女性，声音轻柔，略带气声，亲切自然。" },
        { title: "冷静知性", voice: "一位冷静知性的女性，语调平稳克制，咬字清晰。" },
        { title: "评书男声", voice: "一位说书风格的男声，抑扬顿挫，关键处停顿明显，带笑意。" },
      ],
    },
    {
      id: "breezeTtsClone",
      label: "Breeze 音色克隆 · VOICE_CLONE",
      type: "audio",
    },
    {
      id: "breezeTtsDirection",
      label: "Breeze 声音导演 · VOICE_DIRECTION",
      type: "audio",
      voices: [
        { title: "自然叙述", voice: "保持参考音频的音色，用自然、清晰的叙述语气。" },
        { title: "耳语", voice: "用气声耳语的方式说话，音量很低，像在耳边轻声讲。" },
        { title: "激动", voice: "情绪激动，语速加快，音量提高，声音带一点颤抖。" },
        { title: "悲伤", voice: "情绪低落，语速缓慢，句尾下沉，夹杂轻微叹息。" },
        { title: "严肃", voice: "语速放慢，语气克制而严肃，不带笑意。" },
        { title: "欢快", voice: "语调上扬，节奏轻快，带着明显的笑意。" },
        { title: "疲惫", voice: "声音疲惫，气息偏弱，语速不均匀，像刚熬过一夜。" },
        { title: "旁白播报", voice: "字正腔圆的播报腔，语速稳定，重音落在关键信息上。" },
      ],
    },
  ],
  async generateAudio(this: Context, request: AudioRequest) {
    const base = comfyBase(this.config);
    if (!audioModels.includes(request.model as (typeof audioModels)[number])) throw new Error("不支持的 Breeze TTS 模型");
    // 节点与 agent 工具都把待朗读文本放在 text；prompt 是 AudioRequest 允许的另一入口。
    const text = (request.text ?? request.prompt ?? "").trim();
    if (!text) throw new Error("待朗读文本不能为空");
    if (text.length > maxTextChars) throw new Error(`单次文本过长，请控制在 ${maxTextChars} 字以内`);
    if (request.speed !== undefined && request.speed !== 1) throw new Error("本地 Breeze 不支持调整语速");
    if (request.volume !== undefined && request.volume !== 0) throw new Error("本地 Breeze 不支持音量增益");
    if (request.pitch !== undefined && request.pitch !== 0) throw new Error("本地 Breeze 不支持变调");
    if (request.emotion) throw new Error("本地 Breeze 请用音色下拉或声音描述表达情绪，不支持 emotion 参数");
    if (request.sampleRate !== undefined && request.sampleRate !== nativeRate)
      throw new Error(`本地 Breeze 原生输出 ${nativeRate / 1000} kHz，采样率请选择 ${nativeRate}`);
    if (request.images?.length) throw new Error("音频生成不接收参考图片");

    const needsAudio = request.model !== "breezeTtsDesign";
    const audios = request.audios ?? [];
    if (needsAudio && audios.length !== 1) throw new Error("音色克隆/声音导演需要且仅需要 1 段参考音频");
    if (!needsAudio && audios.length) throw new Error("声音设计不接收参考音频，请改选音色克隆或声音导演");

    const format = request.format ?? "wav";
    const comfyFormat = format === "mp3" ? "mp3" : format === "ogg_opus" ? "opus" : "flac";
    const transcript = audios[0]?.text?.trim() || null;
    const seed = Math.floor(Math.random() * 2 ** 31);
    const timeout = AbortSignal.timeout(60 * 60 * 1000);
    const signal = this.signal ? AbortSignal.any([this.signal, timeout]) : timeout;

    await preflight(this, base, signal, needsAudio && !transcript);
    const file = needsAudio ? await uploadAudio(this, base, audios[0], signal) : null;

    const graph =
      request.model === "breezeTtsDesign"
        ? designGraph(text, request.voice?.trim() || "一位温柔自信的年轻女性，声音清晰，语气亲切，表达轻快而富有感染力。", seed)
        : request.model === "breezeTtsClone"
          ? cloneGraph(text, file as string, transcript, seed)
          : directionGraph(text, file as string, transcript, request.voice?.trim() || "保持参考音频的音色，用自然、清晰的叙述语气。", seed);
    addSave(graph, request.model === "breezeTtsDesign" ? "2" : "4", comfyFormat, `BreezeTTSLocal/${request.model}`);

    const queued = await jsonRequest(this, base, "/prompt", signal, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ prompt: graph, client_id: `breezeTtsLocal${Date.now()}` }),
    });
    const promptId = queued.prompt_id;
    if (typeof promptId !== "string" || !/^[a-f0-9-]{36}$/i.test(promptId)) throw new Error("ComfyUI 未返回有效任务 ID");

    let completed = false;
    const cancel = () => {
      void this.queryFetch(`${base}/api/jobs/${promptId}/cancel`, {
        method: "POST",
        signal: AbortSignal.timeout(10000),
      }).catch(() => {});
    };
    signal.addEventListener("abort", cancel, { once: true });
    if (signal.aborted) cancel();

    try {
      for (;;) {
        signal.throwIfAborted();
        const history = await jsonRequest(this, base, `/history/${promptId}`, signal);
        const record = history[promptId];
        if (record?.status?.status_str === "error") {
          const failure = record.status.messages?.find((entry: any) => entry[0] === "execution_error")?.[1];
          const detail = this.tool.errorMessage(failure?.exception_message ?? record.status.messages);
          throw new Error(`Breeze 语音生成失败（${promptId}）：${detail}`);
        }
        if (record?.status?.completed) {
          completed = true;
          const audio = record.outputs?.[saveNode]?.audio?.[0];
          if (!audio?.filename || audio.type !== "output") throw new Error("ComfyUI 已结束，但未返回音频");
          const query = new URLSearchParams({ filename: audio.filename, subfolder: audio.subfolder ?? "", type: "output" });
          const response = await this.tool.fetch(`${base}/view?${query}`, {
            signal: AbortSignal.any([signal, AbortSignal.timeout(180000)]),
          });
          if (!response.ok) throw new Error(`读取生成音频失败：HTTP ${response.status}`);
          const bytes = new Uint8Array(await response.arrayBuffer());
          if (bytes.length < 16 || bytes.length > maxMediaBytes) throw new Error("返回的音频为空或超过 100 MB");
          const probe = detectAudio(bytes);
          if (!probe) throw new Error("返回的音频格式无效");
          return [{ mediaType: "audio" as const, type: "binary" as const, data: bytes, mimeType: probe.mime }];
        }
        await pause(2000, signal);
      }
    } finally {
      signal.removeEventListener("abort", cancel);
      if (!completed) cancel();
    }
  },
} satisfies ProviderDefinition<typeof rules>;
