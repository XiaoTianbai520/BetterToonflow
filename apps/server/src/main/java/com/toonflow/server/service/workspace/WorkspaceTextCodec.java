package com.toonflow.server.service.workspace;

import com.toonflow.server.common.BusinessException;
import com.toonflow.server.common.RevisionUtils;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class WorkspaceTextCodec {
    private WorkspaceTextCodec() {
    }

    static Snapshot snapshot(byte[] bytes) {
        String encoding = "utf-8";
        int offset = 0;
        if (startsWith(bytes, 0xef, 0xbb, 0xbf)) {
            encoding = "utf-8-bom";
            offset = 3;
        } else if (startsWith(bytes, 0xff, 0xfe)) {
            encoding = "utf-16le";
            offset = 2;
        } else if (startsWith(bytes, 0xfe, 0xff)) {
            encoding = "utf-16be";
            offset = 2;
        }
        String text = decode(bytes, offset, charset(encoding), 415);
        if (text.indexOf('\0') >= 0) {
            throw new BusinessException(415, "不支持读取二进制文件快照", null);
        }
        return new Snapshot(text, encoding, RevisionUtils.sha256(bytes));
    }

    static byte[] encode(byte[] bytes, String encoding) {
        if (encoding == null) {
            return bytes;
        }
        Charset charset = charset(encoding);
        String text = decode(bytes, 0, StandardCharsets.UTF_8, 400);
        // 快照中的 text 已剥离 BOM；这里只为目标编码补回一个 BOM。
        byte[] content = text.getBytes(charset);
        byte[] bom = switch (encoding) {
            case "utf-8-bom" -> new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
            case "utf-16le" -> new byte[]{(byte) 0xff, (byte) 0xfe};
            case "utf-16be" -> new byte[]{(byte) 0xfe, (byte) 0xff};
            default -> new byte[0];
        };
        byte[] result = Arrays.copyOf(bom, bom.length + content.length);
        System.arraycopy(content, 0, result, bom.length, content.length);
        return result;
    }

    static Charset charset(String encoding) {
        return switch (encoding) {
            case "utf-8", "utf-8-bom" -> StandardCharsets.UTF_8;
            case "utf-16le" -> StandardCharsets.UTF_16LE;
            case "utf-16be" -> StandardCharsets.UTF_16BE;
            default -> throw new BusinessException(400, "不支持的文本编码", null);
        };
    }

    private static String decode(byte[] bytes, int offset, Charset charset, int status) {
        try {
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException exception) {
            throw new BusinessException(status, "文件不是合法的受支持编码文本", null);
        }
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (Byte.toUnsignedInt(bytes[index]) != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    record Snapshot(String text, String encoding, String revision) {
    }
}
