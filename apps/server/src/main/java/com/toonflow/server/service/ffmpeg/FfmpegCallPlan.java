package com.toonflow.server.service.ffmpeg;

import com.fasterxml.jackson.databind.JsonNode;
import com.toonflow.server.common.BusinessException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把浏览器侧的 fluent-ffmpeg 链式调用还原为一条 ffmpeg 命令行。
 *
 * <p>参数顺序严格对齐 fluent-ffmpeg 2.3.3：各输入的输入参数与 {@code -i}、{@code -y}、复杂滤镜、
 * 每个输出的音频参数 / {@code -filter:a} / 视频参数 / {@code -filter:v} / 输出参数 / 输出目标。</p>
 *
 * <p>链式调用本身属于可信指令（滤镜与清单内的间接 I/O 不做沙箱），但所有显式文件入口都必须经过
 * {@link FfmpegPathGuard} 校验，禁止访问工作区外的路径。</p>
 */
public final class FfmpegCallPlan {
    public static final Set<String> CHAIN_METHODS = Set.of(
            "input", "addInput", "mergeAdd", "withInputFormat", "inputFormat", "fromFormat",
            "withInputFps", "withInputFPS", "withFpsInput", "withFPSInput", "inputFPS", "inputFps", "fpsInput",
            "FPSInput",
            "nativeFramerate", "withNativeFramerate", "native", "setStartTime", "seekInput", "loop",
            "withNoAudio", "noAudio", "withAudioCodec", "audioCodec", "withAudioBitrate", "audioBitrate",
            "withAudioChannels", "audioChannels", "withAudioFrequency", "audioFrequency", "withAudioQuality",
            "audioQuality", "withAudioFilter", "withAudioFilters", "audioFilter", "audioFilters", "withNoVideo",
            "noVideo", "withVideoCodec", "videoCodec", "withVideoBitrate", "videoBitrate", "withVideoFilter",
            "withVideoFilters", "videoFilter", "videoFilters",
            "withOutputFps", "withOutputFPS", "withFpsOutput", "withFPSOutput", "withFps", "withFPS", "outputFPS",
            "outputFps", "fpsOutput", "FPSOutput", "fps", "FPS",
            "takeFrames", "withFrames", "frames", "keepPixelAspect", "keepDisplayAspect", "keepDisplayAspectRatio",
            "keepDAR",
            "withSize", "setSize", "size", "withAspect", "withAspectRatio", "setAspect", "setAspectRatio", "aspect",
            "aspectRatio",
            "applyAutopadding", "applyAutoPadding", "applyAutopad", "applyAutoPad", "withAutopadding",
            "withAutoPadding", "withAutopad", "withAutoPad", "autoPad", "autopad",
            "addOutput", "output", "seekOutput", "seek", "withDuration", "setDuration", "duration", "toFormat",
            "withOutputFormat", "outputFormat", "format", "map",
            "addInputOption", "addInputOptions", "withInputOption", "withInputOptions", "inputOption", "inputOptions",
            "addOutputOption", "addOutputOptions", "addOption", "addOptions", "withOutputOption", "withOutputOptions",
            "withOption", "withOptions", "outputOption", "outputOptions",
            "filterGraph", "complexFilter", "clone", "renice");

    public static final Set<String> RUN_METHODS = Set.of(
            "run", "save", "saveToFile", "takeScreenshots", "thumbnail", "thumbnails", "screenshot", "screenshots",
            "mergeToFile", "concatenate", "concat");

    public static final Set<String> QUERY_METHODS = Set.of(
            "ffprobe", "availableFilters", "getAvailableFilters", "availableCodecs", "getAvailableCodecs",
            "availableEncoders", "getAvailableEncoders", "availableFormats", "getAvailableFormats");

    public static final Set<String> SCREENSHOT_METHODS = Set.of(
            "takeScreenshots", "thumbnail", "thumbnails", "screenshot", "screenshots");

    public static final Set<String> CONCAT_METHODS = Set.of("mergeToFile", "concatenate", "concat");

    public static final Set<String> SAVE_METHODS = Set.of("save", "saveToFile");

    private record FilterPart(List<String> inputs, String name, String options, List<String> outputs) {
    }

    private static final class Input {
        private final String source;
        private final List<String> options = new ArrayList<>();

        private Input(String source) {
            this.source = source;
        }
    }

    private static final class Output {
        private final List<String> audio = new ArrayList<>();
        private final List<String> audioFilters = new ArrayList<>();
        private final List<String> video = new ArrayList<>();
        private final List<String> videoFilters = new ArrayList<>();
        private final List<String> sizeFilters = new ArrayList<>();
        private final List<String> options = new ArrayList<>();
        private final Map<String, Object> sizeData = new HashMap<>();
        private String target;
    }

    private final Path root;
    private final FfmpegPathGuard guard;
    private final List<Input> inputs = new ArrayList<>();
    private final List<Output> outputs = new ArrayList<>();
    private final List<String> complexFilters = new ArrayList<>();
    private Input currentInput;
    private Output currentOutput;
    private final Integer niceness;
    private final Integer stdoutLines;
    private final Double timeout;
    private final Path cwd;

    public FfmpegCallPlan(Path root, FfmpegPathGuard guard, FfmpegExecuteRequest.Options options) throws IOException {
        this.root = root;
        this.guard = guard;
        this.niceness = options.niceness() != null ? options.niceness()
                : options.priority() != null ? options.priority() : 0;
        this.stdoutLines = options.stdoutLines() != null ? options.stdoutLines() : 100;
        this.timeout = options.timeout();
        this.cwd = options.cwd() == null ? root : guard.directory(root, options.cwd());
        outputs.add(currentOutput = new Output());
        if (options.source() != null) {
            applyInput(guard.file(root, options.source()).toString());
        }
    }

    public Integer niceness() {
        return niceness;
    }

    public Integer stdoutLines() {
        return stdoutLines;
    }

    public Double timeout() {
        return timeout;
    }

    public Path cwd() {
        return cwd;
    }

    public Path root() {
        return root;
    }

    public String source() {
        return inputs.isEmpty() ? null : inputs.getFirst().source;
    }

    public boolean hasTarget() {
        return outputs.stream().anyMatch(output -> output.target != null);
    }

    public void apply(FfmpegExecuteRequest.Call call) {
        String method = call.method();
        if (!CHAIN_METHODS.contains(method)) {
            throw new BusinessException(400, "FFmpeg 配置方法不可用：" + method, null);
        }
        switch (method) {
            case "input", "addInput", "mergeAdd" -> applyInput(guardFile(call));
            case "withInputFormat", "inputFormat", "fromFormat" -> currentInput().options.addAll(
                    List.of("-f", text(call, 0)));
            case "withInputFps", "withInputFPS", "withFpsInput", "withFPSInput", "inputFPS", "inputFps", "fpsInput",
                    "FPSInput" -> currentInput().options.addAll(List.of("-r", text(call, 0)));
            case "nativeFramerate", "withNativeFramerate", "native" -> currentInput().options.add("-re");
            case "setStartTime", "seekInput" -> currentInput().options.addAll(List.of("-ss", text(call, 0)));
            case "loop" -> {
                currentInput().options.addAll(List.of("-loop", "1"));
                JsonNode duration = call.node(0);
                if (duration != null && !duration.isNull()) {
                    currentOutput().options.addAll(List.of("-t", text(call, 0)));
                }
            }
            case "withNoAudio", "noAudio" -> {
                Output output = currentOutput();
                output.audio.clear();
                output.audioFilters.clear();
                output.audio.add("-an");
            }
            case "withAudioCodec", "audioCodec" -> currentOutput().audio.addAll(List.of("-acodec", text(call, 0)));
            case "withAudioBitrate", "audioBitrate" -> currentOutput().audio.addAll(
                    List.of("-b:a", bitrate(call, 0)));
            case "withAudioChannels", "audioChannels" -> currentOutput().audio.addAll(List.of("-ac", text(call, 0)));
            case "withAudioFrequency", "audioFrequency" -> currentOutput().audio.addAll(
                    List.of("-ar", text(call, 0)));
            case "withAudioQuality", "audioQuality" -> currentOutput().audio.addAll(List.of("-aq", text(call, 0)));
            case "withAudioFilter", "withAudioFilters", "audioFilter", "audioFilters" -> currentOutput().audioFilters
                    .addAll(filters(call));
            case "withNoVideo", "noVideo" -> {
                Output output = currentOutput();
                output.video.clear();
                output.videoFilters.clear();
                output.video.add("-vn");
            }
            case "withVideoCodec", "videoCodec" -> currentOutput().video.addAll(List.of("-vcodec", text(call, 0)));
            case "withVideoBitrate", "videoBitrate" -> {
                String bitrate = bitrate(call, 0);
                currentOutput().video.addAll(List.of("-b:v", bitrate));
                JsonNode constant = call.node(1);
                if (constant != null && constant.asBoolean(false)) {
                    currentOutput().video.addAll(List.of("-maxrate", bitrate, "-minrate", bitrate,
                            "-bufsize", "3M"));
                }
            }
            case "withVideoFilter", "withVideoFilters", "videoFilter", "videoFilters" -> currentOutput().videoFilters
                    .addAll(filters(call));
            case "withOutputFps", "withOutputFPS", "withFpsOutput", "withFPSOutput", "withFps", "withFPS",
                    "outputFPS", "outputFps", "fpsOutput", "FPSOutput", "fps", "FPS" -> currentOutput().video.addAll(
                            List.of("-r", text(call, 0)));
            case "takeFrames", "withFrames", "frames" -> currentOutput().video.addAll(
                    List.of("-vframes", text(call, 0)));
            case "keepPixelAspect", "keepDisplayAspect", "keepDisplayAspectRatio", "keepDAR" ->
                    currentOutput().videoFilters.addAll(List.of(
                            "scale=w=if(gt(sar,1),iw*sar,iw):h=if(lt(sar,1),ih/sar,ih)", "setsar=1"));
            case "withSize", "setSize", "size" -> {
                currentOutput().sizeData.put("size", text(call, 0));
                recomputeSizeFilters();
            }
            case "withAspect", "withAspectRatio", "setAspect", "setAspectRatio", "aspect", "aspectRatio" -> {
                currentOutput().sizeData.put("aspect", aspect(call));
                recomputeSizeFilters();
            }
            case "applyAutopadding", "applyAutoPadding", "applyAutopad", "applyAutoPad", "withAutopadding",
                    "withAutoPadding", "withAutopad", "withAutoPad", "autoPad", "autopad" -> {
                JsonNode pad = call.node(0);
                Object value;
                if (pad == null || pad.isNull()) {
                    value = "black";
                } else if (pad.isBoolean()) {
                    value = pad.asBoolean() ? "black" : Boolean.FALSE;
                } else if (pad.isTextual()) {
                    value = pad.asText();
                } else {
                    throw new BusinessException(400, "参数错误", null);
                }
                currentOutput().sizeData.put("pad", value);
                recomputeSizeFilters();
            }
            case "addOutput", "output" -> addOutput(guardFile(call));
            case "seekOutput", "seek" -> currentOutput().options.addAll(List.of("-ss", text(call, 0)));
            case "withDuration", "setDuration", "duration" -> currentOutput().options.addAll(
                    List.of("-t", text(call, 0)));
            case "toFormat", "withOutputFormat", "outputFormat", "format" -> currentOutput().options.addAll(
                    List.of("-f", text(call, 0)));
            case "map" -> currentOutput().options.addAll(List.of("-map", streamSpec(text(call, 0))));
            case "addInputOption", "addInputOptions", "withInputOption", "withInputOptions", "inputOption",
                    "inputOptions" -> currentInput().options.addAll(options(call));
            case "addOutputOption", "addOutputOptions", "addOption", "addOptions", "withOutputOption",
                    "withOutputOptions", "withOption", "withOptions", "outputOption", "outputOptions" ->
                    currentOutput().options.addAll(options(call));
            case "filterGraph", "complexFilter" -> complexFilter(call);
            case "clone" -> {
                // 调用数组已经包含 clone 之前的全部链式方法，状态即克隆结果，无需额外复制。
            }
            case "renice" -> {
                // niceness 在构造时确定，运行中调整仅影响后续进程；当前任务保持不变。
            }
            default -> throw new BusinessException(400, "FFmpeg 配置方法不可用：" + method, null);
        }
    }

    /** save/concat 等终结方法可以通过追加输出来构造命令行；首个输出沿用当前未设目标的输出。 */
    public void addOutput(String target) {
        if (currentOutput.target == null) {
            currentOutput.target = target;
            return;
        }
        Output output = new Output();
        output.target = target;
        outputs.add(currentOutput = output);
    }

    /** 截图配方需要把 size 滤镜串进 split 之前，读取后立即清空避免重复输出。 */
    public List<String> sizeFilters() {
        return new ArrayList<>(currentOutput().sizeFilters);
    }

    public void clearSizeFilters() {
        currentOutput().sizeFilters.clear();
    }

    public boolean hasInput() {
        return !inputs.isEmpty();
    }

    public List<String> arguments() {
        List<String> args = new ArrayList<>();
        for (Input input : inputs) {
            args.addAll(input.options);
            args.add("-i");
            args.add(input.source);
        }
        if (outputs.stream().anyMatch(output -> output.target != null)) {
            args.add("-y");
        }
        args.addAll(complexFilters);
        for (Output output : outputs) {
            args.addAll(output.audio);
            if (!output.audioFilters.isEmpty()) {
                args.add("-filter:a");
                args.add(String.join(",", output.audioFilters));
            }
            args.addAll(output.video);
            List<String> videoFilters = new ArrayList<>(output.videoFilters);
            videoFilters.addAll(output.sizeFilters);
            if (!videoFilters.isEmpty()) {
                args.add("-filter:v");
                args.add(String.join(",", videoFilters));
            }
            args.addAll(output.options);
            if (output.target != null) {
                args.add(output.target);
            }
        }
        return args;
    }

    private void applyInput(String source) {
        Input input = new Input(source);
        inputs.add(currentInput = input);
    }

    private Input currentInput() {
        if (currentInput == null) {
            throw new BusinessException(400, "未指定输入文件", null);
        }
        return currentInput;
    }

    private Output currentOutput() {
        return currentOutput;
    }

    private String guardFile(FfmpegExecuteRequest.Call call) {
        try {
            return guard.file(root, call.text(0)).toString();
        } catch (IOException exception) {
            throw new BusinessException(400, "FFmpeg 文件路径无效", null);
        }
    }

    private void complexFilter(FfmpegExecuteRequest.Call call) {
        JsonNode spec = call.node(0);
        if (spec == null) {
            throw new BusinessException(400, "参数错误", null);
        }
        complexFilters.clear();
        complexFilters.add("-filter_complex");
        if (spec.isArray()) {
            List<String> graphs = new ArrayList<>();
            spec.forEach(item -> graphs.add(filterString(item)));
            complexFilters.add(String.join(";", graphs));
        } else {
            complexFilters.add(filterString(spec));
        }
        JsonNode map = call.node(1);
        if (map != null && map.isArray()) {
            map.forEach(item -> complexFilters.addAll(List.of("-map", streamSpec(item.asText()))));
        } else if (map != null && map.isTextual()) {
            complexFilters.addAll(List.of("-map", streamSpec(map.asText())));
        }
    }

    private List<String> filters(FfmpegExecuteRequest.Call call) {
        List<JsonNode> specs = call.args();
        if (specs.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode spec : specs) {
            if (spec.isArray()) {
                spec.forEach(item -> values.add(filterString(item)));
            } else {
                values.add(filterString(spec));
            }
        }
        return values;
    }

    private String filterString(JsonNode spec) {
        if (spec.isTextual()) {
            return spec.asText();
        }
        if (!spec.isObject()) {
            throw new BusinessException(400, "参数错误", null);
        }
        String name = spec.path("filter").asText("");
        if (name.isEmpty()) {
            throw new BusinessException(400, "参数错误", null);
        }
        StringBuilder filter = new StringBuilder();
        appendStreams(filter, spec.get("inputs"));
        filter.append(name);
        JsonNode options = spec.get("options");
        if (options != null && !options.isNull()) {
            if (options.isTextual() || options.isNumber()) {
                filter.append('=').append(options.asText());
            } else if (options.isArray()) {
                List<String> parts = new ArrayList<>();
                options.forEach(item -> parts.add(quote(item.asText())));
                if (!parts.isEmpty()) {
                    filter.append('=').append(String.join(":", parts));
                }
            } else if (options.isObject() && !options.isEmpty()) {
                List<String> parts = new ArrayList<>();
                options.fields().forEachRemaining(entry -> parts.add(
                        entry.getKey() + "=" + quote(entry.getValue().asText())));
                filter.append('=').append(String.join(":", parts));
            }
        }
        appendStreams(filter, spec.get("outputs"));
        return filter.toString();
    }

    private void appendStreams(StringBuilder target, JsonNode streams) {
        if (streams == null || streams.isNull()) {
            return;
        }
        if (streams.isArray()) {
            streams.forEach(item -> target.append(streamSpec(item.asText())));
        } else if (streams.isTextual()) {
            target.append(streamSpec(streams.asText()));
        }
    }

    /** 与 fluent 的 streamRegexp 一致：允许不带方括号，统一补齐。 */
    private String streamSpec(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return trimmed;
        }
        return "[" + trimmed + "]";
    }

    private String quote(String value) {
        return value.contains(",") ? "'" + value + "'" : value;
    }

    private String text(FfmpegExecuteRequest.Call call, int index) {
        JsonNode value = call.node(index);
        if (value == null || value.isNull()) {
            throw new BusinessException(400, "参数错误", null);
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        throw new BusinessException(400, "参数错误", null);
    }

    private String bitrate(FfmpegExecuteRequest.Call call, int index) {
        return text(call, index).replaceAll("k?$", "k");
    }

    /** 与 fluent 的 outputOptions/inputOptions 一致：单参数允许按空格拆成选项与值。 */
    private List<String> options(FfmpegExecuteRequest.Call call) {
        List<JsonNode> args = call.args();
        boolean split = args.size() == 1;
        List<String> values = new ArrayList<>();
        for (JsonNode arg : args) {
            if (arg.isArray()) {
                arg.forEach(item -> values.add(item.asText()));
            } else if (arg.isTextual() && split) {
                String[] parts = arg.asText().split(" ");
                if (parts.length == 2) {
                    values.add(parts[0]);
                    values.add(parts[1]);
                } else {
                    values.add(arg.asText());
                }
            } else if (arg.isNull()) {
                throw new BusinessException(400, "参数错误", null);
            } else {
                values.add(arg.asText());
            }
        }
        return values;
    }

    private double aspect(FfmpegExecuteRequest.Call call) {
        JsonNode value = call.node(0);
        if (value == null) {
            throw new BusinessException(400, "参数错误", null);
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        String text = text(call, 0);
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            String[] parts = text.split(":");
            if (parts.length == 2) {
                try {
                    return Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
                } catch (NumberFormatException invalid) {
                    throw new BusinessException(400, "Invalid aspect ratio: " + text, null);
                }
            }
            throw new BusinessException(400, "Invalid aspect ratio: " + text, null);
        }
    }

    /** 与 fluent createSizeFilters 对齐：size/aspect/autopad 组合成 scale 与 pad 滤镜。 */
    private void recomputeSizeFilters() {
        Output output = currentOutput();
        output.sizeFilters.clear();
        Map<String, Object> data = output.sizeData;
        if (!data.containsKey("size")) {
            return;
        }
        String size = String.valueOf(data.get("size"));
        Double aspect = (Double) data.get("aspect");
        String pad = data.get("pad") instanceof String color ? color : null;
        var fixedSize = java.util.regex.Pattern.compile("([0-9]+)x([0-9]+)").matcher(size);
        var fixedWidth = java.util.regex.Pattern.compile("([0-9]+)x\\?").matcher(size);
        var fixedHeight = java.util.regex.Pattern.compile("\\?x([0-9]+)").matcher(size);
        var percent = java.util.regex.Pattern.compile("\\b([0-9]{1,3})%").matcher(size);
        if (percent.find()) {
            double ratio = Integer.parseInt(percent.group(1)) / 100.0;
            output.sizeFilters.add("scale=w=trunc(iw*" + number(ratio) + "/2)*2:h=trunc(ih*"
                    + number(ratio) + "/2)*2");
            return;
        }
        if (fixedSize.find()) {
            long width = Math.round(Long.parseLong(fixedSize.group(1)) / 2.0) * 2;
            long height = Math.round(Long.parseLong(fixedSize.group(2)) / 2.0) * 2;
            output.sizeFilters.addAll(scalePad(width, height, (double) width / height, pad));
            return;
        }
        boolean hasWidth = fixedWidth.find();
        boolean hasHeight = fixedHeight.find();
        if (hasWidth || hasHeight) {
            if (aspect != null) {
                long width = hasWidth ? Long.parseLong(fixedWidth.group(1))
                        : Math.round(Long.parseLong(fixedHeight.group(1)) * aspect);
                long height = hasHeight ? Long.parseLong(fixedHeight.group(1))
                        : Math.round(Long.parseLong(fixedWidth.group(1)) / aspect);
                width = Math.round(width / 2.0) * 2;
                height = Math.round(height / 2.0) * 2;
                output.sizeFilters.addAll(scalePad(width, height, aspect, pad));
                return;
            }
            if (hasWidth) {
                long width = Math.round(Long.parseLong(fixedWidth.group(1)) / 2.0) * 2;
                output.sizeFilters.add("scale=w=" + width + ":h=trunc(ow/a/2)*2");
                return;
            }
            long height = Math.round(Long.parseLong(fixedHeight.group(1)) / 2.0) * 2;
            output.sizeFilters.add("scale=w=trunc(oh*a/2)*2:h=" + height);
            return;
        }
        throw new BusinessException(400, "Invalid size specified: " + size, null);
    }

    private List<String> scalePad(long width, long height, double aspect, String color) {
        if (color == null) {
            return List.of("scale=w=" + width + ":h=" + height);
        }
        return List.of(
                "scale=w=if(gt(a," + number(aspect) + ")," + width + ",trunc(" + height + "*a/2)*2)"
                        + ":h=if(lt(a," + number(aspect) + ")," + height + ",trunc(" + width + "/a/2)*2)",
                "pad=w=" + width + ":h=" + height
                        + ":x=if(gt(a," + number(aspect) + "),0,(" + width + "-iw)/2)"
                        + ":y=if(lt(a," + number(aspect) + "),0,(" + height + "-ih)/2)"
                        + ":color=" + color);
    }

    private String number(double value) {
        return value == Math.rint(value) && !Double.isInfinite(value)
                ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** 复杂滤镜中可能出现的多重输入数量（concat 需要）。 */
    public int inputCount() {
        return inputs.size();
    }
}
