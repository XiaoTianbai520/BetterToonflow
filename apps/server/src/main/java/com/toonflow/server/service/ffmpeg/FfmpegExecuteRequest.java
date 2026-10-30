package com.toonflow.server.service.ffmpeg;

import com.fasterxml.jackson.databind.JsonNode;
import com.toonflow.server.common.BusinessException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * POST /api/ffmpeg/execute 的请求体。字段与 apiContract 3.4.8 一致，未知字段直接拒绝。
 */
public record FfmpegExecuteRequest(String requestId, String directory, Options options, List<Call> calls,
        Call operation) {
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Set<String> OPTION_KEYS = Set.of(
            "source", "cwd", "niceness", "priority", "stdoutLines", "timeout");
    private static final Set<String> CALL_KEYS = Set.of("method", "args", "undefinedArgs");
    private static final Set<String> ROOT_KEYS = Set.of(
            "requestId", "directory", "options", "calls", "operation");
    private static final int MAX_CALLS = 2048;
    private static final int MAX_ARGS = 128;

    public record Options(String source, String cwd, Integer niceness, Integer priority, Integer stdoutLines,
            Double timeout) {
    }

    /** 单条 fluent 调用；args 保留原始 JSON，undefinedArgs 标记需要按 undefined 传递的位置。 */
    public record Call(String method, List<JsonNode> args, Set<Integer> undefinedArgs) {
        public boolean has(int index) {
            return index < args.size() && !undefinedArgs.contains(index);
        }

        public JsonNode node(int index) {
            return has(index) ? args.get(index) : null;
        }

        public String text(int index) {
            JsonNode value = node(index);
            if (value == null || !value.isTextual()) {
                throw new BusinessException(400, "参数错误", null);
            }
            return value.asText();
        }

        public String optionalText(int index) {
            JsonNode value = node(index);
            if (value == null || value.isNull()) {
                return null;
            }
            if (!value.isTextual()) {
                throw new BusinessException(400, "参数错误", null);
            }
            return value.asText();
        }
    }

    public static FfmpegExecuteRequest parse(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw invalid();
        }
        Set<String> keys = new HashSet<>();
        body.fieldNames().forEachRemaining(keys::add);
        if (!ROOT_KEYS.containsAll(keys)) {
            throw invalid();
        }
        String requestId = requireText(body.get("requestId"), 1, 64);
        if (!UUID.matcher(requestId).matches()) {
            throw invalid();
        }
        String directory = requireText(body.get("directory"), 1, 4096);
        Options options = parseOptions(body.get("options"));
        List<Call> calls = parseCalls(body.get("calls"));
        Call operation = parseCall(body.get("operation"));
        return new FfmpegExecuteRequest(requestId, directory, options, calls, operation);
    }

    private static Options parseOptions(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalid();
        }
        Set<String> keys = new HashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        if (!OPTION_KEYS.containsAll(keys)) {
            throw invalid();
        }
        String source = optionalText(node.get("source"), 1, 4096);
        String cwd = optionalText(node.get("cwd"), 1, 4096);
        Integer niceness = optionalInt(node.get("niceness"), -20, 20);
        Integer priority = optionalInt(node.get("priority"), -20, 20);
        Integer stdoutLines = optionalInt(node.get("stdoutLines"), 0, Integer.MAX_VALUE);
        Double timeout = optionalNumber(node.get("timeout"), 0);
        return new Options(source, cwd, niceness, priority, stdoutLines, timeout);
    }

    private static List<Call> parseCalls(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > MAX_CALLS) {
            throw invalid();
        }
        List<Call> calls = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            calls.add(parseCall(item));
        }
        return calls;
    }

    private static Call parseCall(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalid();
        }
        Set<String> keys = new HashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        if (!CALL_KEYS.containsAll(keys)) {
            throw invalid();
        }
        String method = requireText(node.get("method"), 1, 64);
        JsonNode argsNode = node.get("args");
        if (argsNode == null || !argsNode.isArray() || argsNode.size() > MAX_ARGS) {
            throw invalid();
        }
        List<JsonNode> args = new ArrayList<>(argsNode.size());
        argsNode.forEach(args::add);
        Set<Integer> undefinedArgs = new HashSet<>();
        JsonNode undefined = node.get("undefinedArgs");
        if (undefined != null) {
            if (!undefined.isArray() || undefined.size() > MAX_ARGS) {
                throw invalid();
            }
            for (JsonNode index : undefined) {
                if (!index.isIntegralNumber() || index.asInt() < 0 || index.asInt() >= MAX_ARGS) {
                    throw invalid();
                }
                undefinedArgs.add(index.asInt());
            }
        }
        return new Call(method, List.copyOf(args), Set.copyOf(undefinedArgs));
    }

    private static String requireText(JsonNode node, int min, int max) {
        String value = optionalText(node, min, max);
        if (value == null) {
            throw invalid();
        }
        return value;
    }

    private static String optionalText(JsonNode node, int min, int max) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw invalid();
        }
        String value = node.asText();
        if (value.length() < min || value.length() > max) {
            throw invalid();
        }
        return value;
    }

    private static Integer optionalInt(JsonNode node, int min, int max) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber()) {
            throw invalid();
        }
        long value = node.asLong();
        if (value < min || value > max) {
            throw invalid();
        }
        return (int) value;
    }

    private static Double optionalNumber(JsonNode node, double min) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw invalid();
        }
        double value = node.asDouble();
        if (!Double.isFinite(value) || value < min) {
            throw invalid();
        }
        return value;
    }

    private static BusinessException invalid() {
        return new BusinessException(400, "参数错误", null);
    }
}
