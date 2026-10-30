package com.toonflow.server.service.ffmpeg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toonflow.server.common.BusinessException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * ffprobe 查询：与 fluent-ffmpeg 相同的 {@code -show_streams -show_format} 输出解析，
 * 结果为 {@code { streams, format, chapters }}，前端可直接读取。
 */
@Service
public class FfmpegProbeRunner {
    private static final long PROBE_TIMEOUT_SECONDS = 60;
    private static final long DURATION_TIMEOUT_SECONDS = 10;
    private final FfmpegProcessRunner runner;
    private final ObjectMapper json;

    public FfmpegProbeRunner(FfmpegProcessRunner runner, ObjectMapper json) {
        this.runner = runner;
        this.json = json;
    }

    public Object probe(Path ffprobe, Path source, List<JsonNode> args, Path cwd, FfmpegTask task) throws IOException {
        List<String> extra = new ArrayList<>();
        boolean asJson = false;
        for (JsonNode arg : args) {
            if (arg == null || arg.isNull()) {
                continue;
            }
            if (arg.isNumber()) {
                if (arg.asInt() != 0) {
                    throw new BusinessException(400, "Invalid input index", null);
                }
            } else if (arg.isArray()) {
                arg.forEach(item -> extra.add(text(item)));
            } else if (arg.isObject()) {
                JsonNode options = arg.get("options");
                if (options != null && options.isArray()) {
                    options.forEach(item -> extra.add(text(item)));
                }
                asJson = arg.path("json").asBoolean(false);
                if (arg.hasNonNull("index") && arg.get("index").asInt() != 0) {
                    throw new BusinessException(400, "Invalid input index", null);
                }
            } else if (arg.isTextual()) {
                extra.add(arg.asText());
            } else {
                throw new BusinessException(400, "参数错误", null);
            }
        }
        List<String> command = new ArrayList<>(List.of("-show_streams", "-show_format"));
        if (asJson) {
            command.addAll(List.of("-print_format", "json"));
        }
        command.addAll(extra);
        command.add(source.toString());
        FfmpegProcessRunner.CaptureResult result = runner.capture(ffprobe, command, cwd,
                PROBE_TIMEOUT_SECONDS, task);
        if (result.exitCode() != 0) {
            throw new IOException(result.stderr().isBlank() ? "FFprobe 执行失败" : result.stderr().trim());
        }
        return asJson ? readJson(result.stdout()) : parse(result.stdout());
    }

    /** 探测输入元数据，供截图配方计算百分比时间点与画面尺寸。 */
    public Map<String, Object> metadata(Path ffprobe, Path source, Path cwd) throws IOException {
        FfmpegProcessRunner.CaptureResult result = runner.capture(ffprobe,
                List.of("-show_streams", "-show_format", source.toString()), cwd, DURATION_TIMEOUT_SECONDS, null);
        if (result.exitCode() != 0) {
            throw new IOException(result.stderr().isBlank() ? "FFprobe 执行失败" : result.stderr().trim());
        }
        return parse(result.stdout());
    }

    /** 探测输入时长，供进度百分比使用；失败时返回 null，不影响主流程。 */
    public Double duration(Path ffprobe, Path source, Path cwd) {
        try {
            Object format = metadata(ffprobe, source, cwd).get("format");
            if (format instanceof Map<?, ?> values && values.get("duration") != null) {
                return Double.valueOf(String.valueOf(values.get("duration")));
            }
        } catch (IOException | NumberFormatException ignored) {
            // 进度百分比属于增强信息，探测失败时退化为不带 percent 的进度事件。
        }
        return null;
    }

    private Object readJson(String stdout) throws IOException {
        try {
            return json.readTree(stdout);
        } catch (IOException exception) {
            throw new IOException("FFprobe 输出解析失败");
        }
    }

    private Map<String, Object> parse(String stdout) {
        List<String> lines = new ArrayList<>();
        for (String line : stdout.split("\\r\\n|\\r|\\n")) {
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        List<Object> streams = new ArrayList<>();
        List<Object> chapters = new ArrayList<>();
        Map<String, Object> format = new LinkedHashMap<>();
        data.put("streams", streams);
        data.put("format", format);
        data.put("chapters", chapters);
        int index = 0;
        while (index < lines.size()) {
            String line = lines.get(index++);
            if (line.regionMatches(true, 0, "[stream", 0, 7)) {
                Map<String, Object> block = new LinkedHashMap<>();
                index = parseBlock(lines, index, "stream", block);
                streams.add(normalize(block));
            } else if (line.regionMatches(true, 0, "[chapter", 0, 8)) {
                Map<String, Object> block = new LinkedHashMap<>();
                index = parseBlock(lines, index, "chapter", block);
                chapters.add(normalize(block));
            } else if (line.equalsIgnoreCase("[format]")) {
                Map<String, Object> block = new LinkedHashMap<>();
                index = parseBlock(lines, index, "format", block);
                format.putAll(normalize(block));
            }
        }
        return data;
    }

    private int parseBlock(List<String> lines, int start, String name, Map<String, Object> block) {
        int index = start;
        while (index < lines.size()) {
            String line = lines.get(index++);
            if (line.equalsIgnoreCase("[/" + name + "]")) {
                return index;
            }
            if (line.startsWith("[")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator < 0) {
                continue;
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!key.startsWith("TAG:") && value.matches("^[0-9]+(\\.[0-9]+)?$")) {
                // 分开装箱：三元表达式会做数值提升，把整数也变成 Double。
                if (value.contains(".")) {
                    block.put(key, Double.valueOf(value));
                } else {
                    block.put(key, Long.valueOf(value));
                }
            } else {
                block.put(key, value);
            }
        }
        return index;
    }

    /** 把 TAG:x 与 DISPOSITION:x 归并到 tags / disposition 子对象，与 fluent 后处理一致。 */
    private Map<String, Object> normalize(Map<String, Object> block) {
        Map<String, Object> tags = new LinkedHashMap<>();
        Map<String, Object> disposition = new LinkedHashMap<>();
        List<String> tagKeys = new ArrayList<>();
        List<String> dispositionKeys = new ArrayList<>();
        block.forEach((key, value) -> {
            if (key.startsWith("TAG:")) {
                tagKeys.add(key);
            } else if (key.startsWith("DISPOSITION:")) {
                dispositionKeys.add(key);
            }
        });
        for (String key : tagKeys) {
            tags.put(key.substring(4), block.remove(key));
        }
        for (String key : dispositionKeys) {
            disposition.put(key.substring(12), block.remove(key));
        }
        if (!tags.isEmpty()) {
            block.put("tags", tags);
        }
        if (!disposition.isEmpty()) {
            block.put("disposition", disposition);
        }
        return block;
    }

    private String text(JsonNode node) {
        if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        throw new BusinessException(400, "参数错误", null);
    }
}
