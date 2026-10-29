package com.toonflow.server.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

public final class RevisionUtils {
    private RevisionUtils() {
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", exception);
        }
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    public static void requireMatch(String expectedRevision, String currentRevision) {
        if (expectedRevision == null || !expectedRevision.matches("[a-f0-9]{64}")) {
            throw new BusinessException(400, "revision 必须为 SHA-256 小写摘要", null);
        }
        if (!expectedRevision.equals(currentRevision)) {
            throw new BusinessException(409, "内容已被其他位置修改或删除，请刷新后再试", Map.of("code", "ESTALE"));
        }
    }
}
