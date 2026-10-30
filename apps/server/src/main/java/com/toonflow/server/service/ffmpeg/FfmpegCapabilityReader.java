package com.toonflow.server.service.ffmpeg;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * {@code ffmpeg -filters/-codecs/-encoders/-formats} 能力查询，输出结构与 fluent 的同名方法一致。
 * 结果按二进制路径缓存，避免每次查询都重新拉起进程。
 */
@Service
public class FfmpegCapabilityReader {
    private static final long TIMEOUT_SECONDS = 30;
    private static final Pattern AV_CODEC = Pattern.compile(
            "^\\s*([D ])([E ])([VAS])([S ])([D ])([T ]) ([^ ]+) +(.*)$");
    private static final Pattern FF_CODEC = Pattern.compile(
            "^\\s*([D.])([E.])([VAS])([I.])([L.])([S.]) ([^ ]+) +(.*)$");
    private static final Pattern ENCODERS_IN_DESCRIPTION = Pattern.compile("\\(encoders:([^)]+)\\)");
    private static final Pattern DECODERS_IN_DESCRIPTION = Pattern.compile("\\(decoders:([^)]+)\\)");
    private static final Pattern ENCODER = Pattern.compile(
            "^\\s*([VAS.])([F.])([S.])([X.])([B.])([D.]) ([^ ]+) +(.*)$");
    private static final Pattern FORMAT = Pattern.compile("^\\s*([D ])([E ])\\s+([^ ]+)\\s+(.*)$");
    private static final Pattern FILTER = Pattern.compile(
            "^(?: [T.][S.][C.] )?([^ ]+) +(AA?|VV?|\\|)->(AA?|VV?|\\|) +(.*)$");
    private final FfmpegProcessRunner runner;
    private final Map<String, Map<String, Object>> cache = new ConcurrentHashMap<>();

    public FfmpegCapabilityReader(FfmpegProcessRunner runner) {
        this.runner = runner;
    }

    public Map<String, Object> read(Path ffmpeg, String method, Path cwd, FfmpegTask task) throws IOException {
        String flag = switch (method) {
            case "availableFilters", "getAvailableFilters" -> "-filters";
            case "availableCodecs", "getAvailableCodecs" -> "-codecs";
            case "availableEncoders", "getAvailableEncoders" -> "-encoders";
            case "availableFormats", "getAvailableFormats" -> "-formats";
            default -> throw new IllegalArgumentException("不支持的能力查询：" + method);
        };
        String key = ffmpeg + "|" + flag;
        Map<String, Object> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        FfmpegProcessRunner.CaptureResult result = runner.capture(ffmpeg, List.of(flag), cwd,
                TIMEOUT_SECONDS, task);
        if (result.exitCode() != 0) {
            throw new IOException(result.stderr().isBlank() ? "FFmpeg 能力查询失败" : result.stderr().trim());
        }
        Map<String, Object> parsed = switch (flag) {
            case "-filters" -> parseFilters(result.stdout());
            case "-codecs" -> parseCodecs(result.stdout());
            case "-encoders" -> parseEncoders(result.stdout());
            default -> parseFormats(result.stdout());
        };
        cache.put(key, parsed);
        return parsed;
    }

    private Map<String, Object> parseFilters(String stdout) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (String line : lines(stdout)) {
            Matcher match = FILTER.matcher(line);
            if (!match.find()) {
                continue;
            }
            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("description", match.group(4));
            filter.put("input", type(match.group(2).charAt(0)));
            filter.put("multipleInputs", match.group(2).length() > 1);
            filter.put("output", type(match.group(3).charAt(0)));
            filter.put("multipleOutputs", match.group(3).length() > 1);
            data.put(match.group(1), filter);
        }
        return data;
    }

    private Map<String, Object> parseCodecs(String stdout) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (String line : lines(stdout)) {
            Matcher legacy = AV_CODEC.matcher(line);
            if (legacy.find() && !"?".equals(legacy.group(7)) && !"=".equals(legacy.group(7))) {
                data.put(legacy.group(7), codec(legacy.group(3), legacy.group(8),
                        "D".equals(legacy.group(1)), "E".equals(legacy.group(2)),
                        Map.of("drawHorizBand", "S".equals(legacy.group(4)),
                                "directRendering", "D".equals(legacy.group(5)),
                                "weirdFrameTruncation", "T".equals(legacy.group(6)))));
            }
            Matcher modern = FF_CODEC.matcher(line);
            if (!modern.find() || "=".equals(modern.group(7))) {
                continue;
            }
            Map<String, Object> details = codec(modern.group(3), modern.group(8),
                    "D".equals(modern.group(1)), "E".equals(modern.group(2)),
                    Map.of("intraFrameOnly", "I".equals(modern.group(4)),
                            "isLossy", "L".equals(modern.group(5)),
                            "isLossless", "S".equals(modern.group(6))));
            String description = modern.group(8);
            data.put(modern.group(7), details);
            for (String name : matches(DECODERS_IN_DESCRIPTION, description)) {
                Map<String, Object> decoder = data.get(name) instanceof Map<?, ?> existing
                        ? cast(existing) : new LinkedHashMap<>(details);
                decoder.put("canDecode", true);
                if (!decoder.containsKey("canEncode")) {
                    decoder.put("canEncode", false);
                }
                data.put(name, decoder);
            }
            for (String name : matches(ENCODERS_IN_DESCRIPTION, description)) {
                Map<String, Object> encoder = data.get(name) instanceof Map<?, ?> existing
                        ? cast(existing) : new LinkedHashMap<>(details);
                encoder.put("canEncode", true);
                if (!encoder.containsKey("canDecode")) {
                    encoder.put("canDecode", false);
                }
                data.put(name, encoder);
            }
        }
        return data;
    }

    private Map<String, Object> parseEncoders(String stdout) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (String line : lines(stdout)) {
            Matcher match = ENCODER.matcher(line);
            if (!match.find() || "=".equals(match.group(7))) {
                continue;
            }
            Map<String, Object> encoder = new LinkedHashMap<>();
            encoder.put("type", type(match.group(1).charAt(0)));
            encoder.put("description", match.group(8));
            encoder.put("frameMT", "F".equals(match.group(2)));
            encoder.put("sliceMT", "S".equals(match.group(3)));
            encoder.put("experimental", "X".equals(match.group(4)));
            encoder.put("drawHorizBand", "B".equals(match.group(5)));
            encoder.put("directRendering", "D".equals(match.group(6)));
            data.put(match.group(7), encoder);
        }
        return data;
    }

    private Map<String, Object> parseFormats(String stdout) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (String line : lines(stdout)) {
            Matcher match = FORMAT.matcher(line);
            if (!match.find()) {
                continue;
            }
            for (String format : match.group(3).split(",")) {
                Map<String, Object> value = data.get(format) instanceof Map<?, ?> existing
                        ? cast(existing) : new LinkedHashMap<>();
                if (!value.containsKey("description")) {
                    value.put("description", match.group(4));
                    value.put("canDemux", false);
                    value.put("canMux", false);
                }
                if ("D".equals(match.group(1))) {
                    value.put("canDemux", true);
                }
                if ("E".equals(match.group(2))) {
                    value.put("canMux", true);
                }
                data.put(format, value);
            }
        }
        return data;
    }

    private Map<String, Object> codec(String type, String description, boolean canDecode, boolean canEncode,
            Map<String, Object> extra) {
        Map<String, Object> codec = new LinkedHashMap<>();
        codec.put("type", type(type.charAt(0)));
        codec.put("description", description);
        codec.put("canDecode", canDecode);
        codec.put("canEncode", canEncode);
        codec.putAll(extra);
        return codec;
    }

    private String type(char flag) {
        return switch (flag) {
            case 'V' -> "video";
            case 'A' -> "audio";
            case 'S' -> "subtitle";
            default -> "none";
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }

    private List<String> matches(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String name : matcher.group(1).trim().split(" ")) {
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    private List<String> lines(String stdout) {
        List<String> lines = new ArrayList<>();
        for (String line : stdout.split("\\r\\n|\\r|\\n")) {
            lines.add(line);
        }
        return lines;
    }
}
