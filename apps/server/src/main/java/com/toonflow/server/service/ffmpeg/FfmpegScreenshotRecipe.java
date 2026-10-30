package com.toonflow.server.service.ffmpeg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toonflow.server.common.BusinessException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 截图配方：把 count / timemarks / size / filename 配置展开成多次 output + 一个 split 复杂滤镜，
 * 与 fluent-ffmpeg 的 screenshots() 行为对齐，额外修正 size 滤镜缺少流标签导致图不合法的问题。
 */
@Service
public class FfmpegScreenshotRecipe {
    private static final Pattern PERCENT = Pattern.compile("^([\\d.]+)%$");
    private static final Pattern INDEX_TOKEN = Pattern.compile("%(0*)i");
    private static final Pattern FIXED_SIZE = Pattern.compile("^(\\d+)x(\\d+)$");
    private static final Pattern FIXED_WIDTH = Pattern.compile("^(\\d+)x\\?$");
    private static final Pattern FIXED_HEIGHT = Pattern.compile("^\\?x(\\d+)$");
    private static final Pattern PERCENT_SIZE = Pattern.compile("^(\\d+)%$");
    private final ObjectMapper json;
    private final FfmpegProbeRunner probe;
    private final FfmpegPathGuard guard;

    public FfmpegScreenshotRecipe(ObjectMapper json, FfmpegProbeRunner probe, FfmpegPathGuard guard) {
        this.json = json;
        this.probe = probe;
        this.guard = guard;
    }

    /** 展开调用链并返回已生成的文件名列表，调用方随后负责执行与发送 filenames 事件。 */
    public List<String> prepare(Path root, Path cwd, FfmpegCallPlan plan, FfmpegExecuteRequest.Call operation,
            Path ffprobe, FfmpegTask task) throws IOException {
        JsonNode config = operation.node(0);
        JsonNode folderArg = operation.node(1);
        int count = 1;
        String folder = null;
        String filename = "tn.png";
        String sizeSpec = null;
        JsonNode timemarks = null;
        if (config != null && config.isNumber()) {
            count = config.asInt();
        } else if (config != null && config.isObject()) {
            count = config.path("count").asInt(0);
            folder = config.hasNonNull("folder") ? config.get("folder").asText() : null;
            filename = config.hasNonNull("filename") ? config.get("filename").asText() : "tn.png";
            sizeSpec = config.hasNonNull("size") ? config.get("size").asText() : null;
            timemarks = config.has("timemarks") ? config.get("timemarks") : config.get("timestamps");
        } else if (config != null && !config.isNull()) {
            throw new BusinessException(400, "参数错误", null);
        }
        if (folder == null && folderArg != null && folderArg.isTextual()) {
            folder = folderArg.asText();
        }
        if (folder == null || folder.isBlank()) {
            folder = ".";
        }
        if (timemarks == null || timemarks.isNull()) {
            if (count <= 0) {
                throw new BusinessException(400, "Cannot take screenshots: neither a count nor a timemark list are specified", null);
            }
            double interval = 100.0 / (1 + count);
            var generated = json.createArrayNode();
            for (int index = 0; index < count; index++) {
                generated.add(number(interval * (index + 1)) + "%");
            }
            timemarks = generated;
        }
        if (!timemarks.isArray()) {
            throw new BusinessException(400, "参数错误", null);
        }
        List<JsonNode> marks = new ArrayList<>();
        timemarks.forEach(marks::add);
        boolean needDuration = marks.stream().anyMatch(mark -> mark.isTextual() && PERCENT.matcher(mark.asText()).find());
        Path source = Path.of(plan.source());
        Map<String, Object> metadata = needDuration || sizeSpec != null ? probe.metadata(ffprobe, source, cwd) : null;
        if (needDuration) {
            double duration = duration(metadata);
            if (duration <= 0) {
                throw new BusinessException(400, "Could not get input duration, please specify fixed timemarks", null);
            }
            List<Double> resolved = new ArrayList<>();
            for (JsonNode mark : marks) {
                Matcher percent = mark.isTextual() ? PERCENT.matcher(mark.asText()) : null;
                resolved.add(percent != null && percent.find()
                        ? duration * Double.parseDouble(percent.group(1)) / 100 : seconds(mark));
            }
            resolved.sort(Comparator.naturalOrder());
            List<JsonNode> normalized = new ArrayList<>();
            resolved.forEach(value -> normalized.add(json.valueToTree(value)));
            marks = normalized;
        } else {
            List<Double> resolved = new ArrayList<>();
            for (JsonNode mark : marks) {
                resolved.add(seconds(mark));
            }
            resolved.sort(Comparator.naturalOrder());
            List<JsonNode> normalized = new ArrayList<>();
            resolved.forEach(value -> normalized.add(json.valueToTree(value)));
            marks = normalized;
        }

        String pattern = filename.isEmpty() ? "tn.png" : filename;
        if (!pattern.contains(".")) {
            pattern += ".png";
        }
        if (marks.size() > 1 && !Pattern.compile("%(s|0*i)").matcher(pattern).find()) {
            int dot = pattern.lastIndexOf('.');
            pattern = pattern.substring(0, dot) + "_%i" + pattern.substring(dot);
        }
        Path input = Path.of(plan.source());
        if (pattern.contains("%f") || pattern.contains("%b")) {
            String name = input.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            pattern = pattern.replace("%f", name).replace("%b", base);
        }
        if (pattern.matches(".*%[whr].*")) {
            if (sizeSpec != null) {
                validateSize(sizeSpec);
            }
            double width;
            double height;
            Matcher fixed = sizeSpec == null ? null : FIXED_SIZE.matcher(sizeSpec);
            if (fixed != null && fixed.find()) {
                // 固定尺寸无需探测，直接使用配置值，与 fluent 行为一致。
                width = Double.parseDouble(fixed.group(1));
                height = Double.parseDouble(fixed.group(2));
            } else {
                if (metadata == null) {
                    metadata = probe.metadata(ffprobe, source, cwd);
                }
                int[] video = videoSize(metadata);
                width = video[0];
                height = video[1];
                if (sizeSpec != null) {
                    Matcher fixedWidth = FIXED_WIDTH.matcher(sizeSpec);
                    Matcher fixedHeight = FIXED_HEIGHT.matcher(sizeSpec);
                    Matcher percent = PERCENT_SIZE.matcher(sizeSpec);
                    if (fixedWidth.find()) {
                        height = height * Double.parseDouble(fixedWidth.group(1)) / width;
                        width = Double.parseDouble(fixedWidth.group(1));
                    } else if (fixedHeight.find()) {
                        width = width * Double.parseDouble(fixedHeight.group(1)) / height;
                        height = Double.parseDouble(fixedHeight.group(1));
                    } else if (percent.find()) {
                        width = width * Double.parseDouble(percent.group(1)) / 100;
                        height = height * Double.parseDouble(percent.group(1)) / 100;
                    }
                }
            }
            if (width <= 0 || height <= 0) {
                throw new BusinessException(400, "No video stream in input, cannot replace %w, %h or %r", null);
            }
            long evenWidth = Math.round(width / 2) * 2;
            long evenHeight = Math.round(height / 2) * 2;
            pattern = pattern.replace("%r", evenWidth + "x" + evenHeight)
                    .replace("%w", String.valueOf(evenWidth))
                    .replace("%h", String.valueOf(evenHeight));
        }

        if (marks.isEmpty()) {
            throw new BusinessException(400, "Cannot take screenshots: neither a count nor a timemark list are specified", null);
        }
        Path folderPath = planPath(root, folder);
        Files.createDirectories(folderPath);
        List<String> filenames = new ArrayList<>();
        for (int index = 0; index < marks.size(); index++) {
            String name = replaceIndexToken(pattern, index)
                    .replace("%s", number(seconds(marks.get(index))));
            Path target = folderPath.resolve(name).normalize();
            if (!target.startsWith(folderPath)) {
                throw new BusinessException(403, "FFmpeg 只能操作当前工作区内的文件", Map.of("code", "EACCES"));
            }
            Files.createDirectories(target.getParent());
            filenames.add(target.toString());
        }

        // 截图尺寸需要作用到输出画面上，与 fluent 在 runCommand 中调用 size() 的顺序一致。
        if (sizeSpec != null) {
            validateSize(sizeSpec);
            plan.apply(call("size", json.valueToTree(sizeSpec)));
        }
        List<String> sizeFilters = plan.sizeFilters();
        List<String> chains = new ArrayList<>();
        String previous = null;
        for (int index = 0; index < sizeFilters.size(); index++) {
            String label = "size" + index;
            chains.add((previous == null ? "" : "[" + previous + "]") + sizeFilters.get(index) + "[" + label + "]");
            previous = label;
        }
        StringBuilder split = new StringBuilder(previous == null ? "" : "[" + previous + "]");
        split.append("split=").append(marks.size());
        for (int index = 0; index < marks.size(); index++) {
            split.append("[screen").append(index).append("]");
        }
        chains.add(split.toString());
        plan.clearSizeFilters();
        plan.apply(call("complexFilter", json.valueToTree(String.join(";", chains))));

        double first = seconds(marks.getFirst());
        for (int index = 0; index < marks.size(); index++) {
            plan.apply(call("output", json.valueToTree(filenames.get(index))));
            plan.apply(call("frames", json.valueToTree(1)));
            plan.apply(call("map", json.valueToTree("screen" + index)));
            if (index == 0) {
                plan.apply(call("seekInput", json.valueToTree(number(first))));
            } else {
                plan.apply(call("seek", json.valueToTree(number(seconds(marks.get(index)) - first))));
            }
        }
        return filenames;
    }

    private Path planPath(Path root, String folder) {
        try {
            return guard.path(root, folder);
        } catch (IOException exception) {
            throw new BusinessException(400, "FFmpeg 目录不存在：" + folder, null);
        }
    }

    private void validateSize(String size) {
        if (!FIXED_SIZE.matcher(size).find() && !FIXED_WIDTH.matcher(size).find()
                && !FIXED_HEIGHT.matcher(size).find() && !PERCENT_SIZE.matcher(size).find()) {
            throw new BusinessException(400, "Invalid size parameter: " + size, null);
        }
    }

    @SuppressWarnings("unchecked")
    private double duration(Map<String, Object> metadata) {
        double duration = 0;
        if (metadata.get("streams") instanceof List<?> streams) {
            for (Object item : streams) {
                if (item instanceof Map<?, ?> stream && "video".equals(stream.get("codec_type"))
                        && stream.get("duration") instanceof Number value) {
                    duration = Math.max(duration, value.doubleValue());
                }
            }
        }
        if (duration <= 0 && metadata.get("format") instanceof Map<?, ?> format
                && format.get("duration") instanceof Number value) {
            duration = value.doubleValue();
        }
        return duration;
    }

    private int[] videoSize(Map<String, Object> metadata) {
        long width = 0;
        long height = 0;
        if (metadata.get("streams") instanceof List<?> streams) {
            for (Object item : streams) {
                if (item instanceof Map<?, ?> stream && "video".equals(stream.get("codec_type"))
                        && stream.get("width") instanceof Number streamWidth
                        && stream.get("height") instanceof Number streamHeight) {
                    if (streamWidth.longValue() * streamHeight.longValue() > width * height) {
                        width = streamWidth.longValue();
                        height = streamHeight.longValue();
                    }
                }
            }
        }
        if (width <= 0 || height <= 0) {
            throw new BusinessException(400, "No video stream in input, cannot replace %w, %h or %r", null);
        }
        return new int[] {(int) width, (int) height};
    }

    private String replaceIndexToken(String pattern, int index) {
        Matcher matcher = INDEX_TOKEN.matcher(pattern);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String padding = matcher.group(1);
            String value = String.valueOf(index + 1);
            String padded = "0".repeat(Math.max(0, padding.length() + 1 - value.length())) + value;
            matcher.appendReplacement(result, Matcher.quoteReplacement(padded));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private double seconds(JsonNode mark) {
        if (mark.isNumber()) {
            return mark.asDouble();
        }
        String text = mark.asText();
        if (!text.contains(":") && text.contains(".")) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        String[] parts = text.split(":");
        double seconds = 0;
        try {
            seconds = Double.parseDouble(parts[parts.length - 1]);
            if (parts.length >= 2) {
                seconds += Double.parseDouble(parts[parts.length - 2]) * 60;
            }
            if (parts.length >= 3) {
                seconds += Double.parseDouble(parts[parts.length - 3]) * 3600;
            }
        } catch (NumberFormatException ignored) {
            return 0;
        }
        return seconds;
    }

    private String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private FfmpegExecuteRequest.Call call(String method, JsonNode... args) {
        return new FfmpegExecuteRequest.Call(method, List.of(args), Set.of());
    }
}
