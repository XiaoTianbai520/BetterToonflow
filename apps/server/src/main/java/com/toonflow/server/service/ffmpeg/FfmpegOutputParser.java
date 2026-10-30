package com.toonflow.server.service.ffmpeg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 ffmpeg stderr：进度行、输入编解码信息。
 * 规则与 fluent-ffmpeg 的 extractProgress / extractCodecData 保持一致，前端事件参数可直接复用。
 */
final class FfmpegOutputParser {
    private static final Pattern INPUT = Pattern.compile("Input #[0-9]+, ([^ ]+),");
    private static final Pattern DURATION = Pattern.compile("Duration: ([^,]+)");
    private static final Pattern AUDIO = Pattern.compile("Audio: (.*)");
    private static final Pattern VIDEO = Pattern.compile("Video: (.*)");
    private static final Pattern OUTPUT = Pattern.compile("Output #[0-9]+");
    private static final Pattern STOP_HINT = Pattern.compile("Stream mapping:|Press (\\[q\\]|ctrl-c) to stop");

    private final Double durationSeconds;
    private Map<String, Object> codec;
    private boolean inInput;
    private boolean codecEmitted;

    FfmpegOutputParser(Double durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    /** 累积输入信息，返回 true 表示可以发送 codecData 事件（仅第一条输入）。 */
    boolean acceptCodecLine(String line) {
        if (codecEmitted) {
            return false;
        }
        Matcher input = INPUT.matcher(line);
        Matcher duration = DURATION.matcher(line);
        Matcher audio = AUDIO.matcher(line);
        Matcher video = VIDEO.matcher(line);
        if (input.find()) {
            if (codec == null) {
                codec = new LinkedHashMap<>();
                codec.put("format", input.group(1));
                codec.put("audio", "");
                codec.put("video", "");
                codec.put("duration", "");
            }
            inInput = true;
            return false;
        }
        if (codec == null) {
            return false;
        }
        if (inInput && duration.find()) {
            codec.put("duration", duration.group(1));
        } else if (inInput && audio.find()) {
            List<String> details = split(audio.group(1));
            codec.put("audio", details.isEmpty() ? "" : details.getFirst());
            codec.put("audio_details", details);
        } else if (inInput && video.find()) {
            List<String> details = split(video.group(1));
            codec.put("video", details.isEmpty() ? "" : details.getFirst());
            codec.put("video_details", details);
        } else if (OUTPUT.matcher(line).find()) {
            inInput = false;
        } else if (STOP_HINT.matcher(line).find()) {
            codecEmitted = true;
            return true;
        }
        return false;
    }

    Map<String, Object> codecData() {
        if (codec == null) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("format", codec.getOrDefault("format", ""));
        data.put("audio", codec.getOrDefault("audio", ""));
        data.put("video", codec.getOrDefault("video", ""));
        data.put("duration", codec.getOrDefault("duration", ""));
        if (codec.containsKey("audio_details")) {
            data.put("audio_details", codec.get("audio_details"));
        }
        if (codec.containsKey("video_details")) {
            data.put("video_details", codec.get("video_details"));
        }
        return data;
    }

    /** 非进度行返回 null；percent 仅在已探测到输入时长时提供。 */
    Map<String, Object> progress(String line) {
        Map<String, String> parsed = parseProgressLine(line);
        if (parsed == null) {
            return null;
        }
        Map<String, Object> progress = new LinkedHashMap<>();
        putNumber(progress, "frames", parseInt(parsed.get("frame")));
        putNumber(progress, "currentFps", parseInt(parsed.get("fps")));
        String bitrate = parsed.get("bitrate");
        if (bitrate != null) {
            try {
                progress.put("currentKbps", Double.parseDouble(bitrate.replace("kbits/s", "").trim()));
            } catch (NumberFormatException ignored) {
                // 非法码率不阻断进度解析。
            }
        }
        putNumber(progress, "targetSize", parseInt(parsed.get("size") != null
                ? parsed.get("size") : parsed.get("Lsize")));
        String timemark = parsed.get("time");
        if (timemark != null) {
            progress.put("timemark", timemark);
        }
        if (durationSeconds != null && durationSeconds > 0 && timemark != null) {
            progress.put("percent", Math.round(timemarkSeconds(timemark) / durationSeconds * 10000.0) / 100.0);
        }
        return progress.isEmpty() ? null : progress;
    }

    private Map<String, String> parseProgressLine(String line) {
        String normalized = line.replaceAll("=\\s+", "=").trim();
        if (normalized.isEmpty()) {
            return null;
        }
        Map<String, String> progress = new LinkedHashMap<>();
        for (String part : normalized.split(" ")) {
            int separator = part.indexOf('=');
            if (separator < 0) {
                return null;
            }
            progress.put(part.substring(0, separator), part.substring(separator + 1));
        }
        return progress;
    }

    private List<String> split(String value) {
        List<String> details = new ArrayList<>();
        for (String item : value.split(", ")) {
            details.add(item);
        }
        return details;
    }

    private Integer parseInt(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private double timemarkSeconds(String timemark) {
        if (!timemark.contains(":") && timemark.contains(".")) {
            try {
                return Double.parseDouble(timemark);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        String[] parts = timemark.split(":");
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

    private void putNumber(Map<String, Object> target, String key, Integer value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
