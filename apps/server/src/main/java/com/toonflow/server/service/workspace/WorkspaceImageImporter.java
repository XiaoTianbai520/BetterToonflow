package com.toonflow.server.service.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toonflow.server.common.BusinessException;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceImageImporter {
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private final ObjectMapper json;

    public WorkspaceImageImporter(ObjectMapper json) {
        this.json = json;
    }

    public Image load(String url) throws IOException {
        if (url == null || url.isEmpty() || url.length() > 16384) {
            throw invalid("图片地址长度必须为 1..16384");
        }
        byte[] bytes = url.startsWith("data:image/") ? decodeData(url) : download(url);
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new BusinessException(413, "图片不能超过 20MB", null);
        }
        String extension = extension(bytes);
        String mimeType = switch (extension) {
            case "jpg" -> "image/jpeg";
            case "ico" -> "image/x-icon";
            default -> "image/" + extension;
        };
        return new Image(bytes, mimeType, extension);
    }

    private byte[] download(String value) throws IOException {
        URI uri = remoteUri(value);
        long deadline = System.nanoTime() + 30_000_000_000L;
        for (int redirects = 0; redirects <= 5; redirects++) {
            try (Socket socket = connect(uri, deadline)) {
                String path = uri.getRawPath();
                if (path == null || path.isEmpty()) {
                    path = "/";
                }
                if (uri.getRawQuery() != null) {
                    path += "?" + uri.getRawQuery();
                }
                String request = "GET " + path + " HTTP/1.1\r\nHost: " + uri.getRawAuthority()
                        + "\r\nAccept: image/*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                InputStream stream = new BufferedInputStream(socket.getInputStream());
                Headers headers = readHeaders(stream, socket, deadline);
                int status = headers.status();
                if (REDIRECTS.contains(status)) {
                    String location = headers.values().get("location");
                    if (location == null || redirects == 5) {
                        throw invalid("图片重定向无效或超过限制");
                    }
                    uri = remoteUri(uri.resolve(location).toString());
                    continue;
                }
                if (status < 200 || status >= 300) {
                    throw invalid("下载图片失败");
                }
                String length = headers.values().get("content-length");
                long contentLength = length == null ? -1 : Long.parseLong(length);
                if (contentLength > MAX_IMAGE_BYTES) {
                    throw new BusinessException(413, "图片不能超过 20MB", null);
                }
                String transfer = headers.values().get("transfer-encoding");
                String encoding = headers.values().get("content-encoding");
                if ((encoding != null && !encoding.equalsIgnoreCase("identity"))
                        || (transfer != null && !transfer.equalsIgnoreCase("chunked"))) {
                    throw invalid("图片服务器返回不支持的传输编码");
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (transfer != null) {
                    while (true) {
                        String chunk = readLine(stream, socket, deadline, 4096).split(";", 2)[0].trim();
                        long count = Long.parseLong(chunk, 16);
                        if (count == 0) {
                            break;
                        }
                        if (count < 0) {
                            throw invalid("图片分块长度无效");
                        }
                        readBody(stream, socket, deadline, output, count);
                        if (!readLine(stream, socket, deadline, 2).isEmpty()) {
                            throw invalid("图片分块格式无效");
                        }
                    }
                } else {
                    if (contentLength < -1) {
                        throw invalid("图片长度无效");
                    }
                    readBody(stream, socket, deadline, output, contentLength);
                }
                return output.toByteArray();
            } catch (IOException | IllegalArgumentException exception) {
                throw invalid("图片地址无效或下载失败");
            }
        }
        throw invalid("图片重定向超过限制");
    }

    private Socket connect(URI uri, long deadline) throws IOException {
        String host = uri.getHost().replace("[", "").replace("]", "");
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length > 0 && java.util.Arrays.stream(addresses).allMatch(this::isFakeAddress)) {
            addresses = resolvePublicDns(host, deadline);
        }
        if (addresses.length == 0) {
            throw invalid("无法解析图片地址");
        }
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address.getAddress())) {
                throw invalid("不能下载本机、内网或特殊网络地址的图片");
            }
        }
        boolean secure = uri.getScheme().equalsIgnoreCase("https");
        int port = uri.getPort() == -1 ? (secure ? 443 : 80) : uri.getPort();
        Socket socket = new Socket();
        try {
            // 固定已校验 IP，避免校验与连接之间再次 DNS 解析导致重绑定；TLS 仍校验原域名。
            socket.connect(new InetSocketAddress(addresses[0], port), remainingMillis(deadline));
            socket.setSoTimeout(remainingMillis(deadline));
            if (!secure) {
                return socket;
            }
            SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(socket, host, port, true);
            socket = tls;
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            if (!host.contains(":") && !host.matches("[0-9.]+")) {
                parameters.setServerNames(List.of(new SNIHostName(host)));
            }
            tls.setSSLParameters(parameters);
            tls.setSoTimeout(remainingMillis(deadline));
            tls.startHandshake();
            return tls;
        } catch (IOException | RuntimeException exception) {
            socket.close();
            throw exception;
        }
    }

    private boolean isFakeAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && Byte.toUnsignedInt(bytes[0]) == 198
                && (Byte.toUnsignedInt(bytes[1]) == 18 || Byte.toUnsignedInt(bytes[1]) == 19);
    }

    private InetAddress[] resolvePublicDns(String host, long deadline) throws IOException {
        // 与现有 Runtime 一致：仅 TUN fake-IP 可走固定 DoH，不把特殊网段直接作为下载目标。
        URI endpoint = URI.create("https://dns.alidns.com/resolve?name="
                + URLEncoder.encode(host, StandardCharsets.UTF_8) + "&type=A");
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(remainingMillis(deadline)))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMillis(remainingMillis(deadline))).GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw invalid("无法解析图片的真实公网地址");
                }
                JsonNode result = json.readTree(WorkspaceService.readLimited(body, 64 * 1024));
                List<InetAddress> addresses = new ArrayList<>();
                if (result != null && result.path("Status").asInt(-1) == 0 && result.path("Answer").isArray()) {
                    for (JsonNode answer : result.path("Answer")) {
                        String address = answer.path("data").asText("");
                        if (answer.path("type").asInt() == 1 && address.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) {
                            addresses.add(InetAddress.getByName(address));
                        }
                    }
                }
                return addresses.toArray(InetAddress[]::new);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("图片地址解析中断", exception);
        }
    }

    private boolean isPublicAddress(byte[] bytes) {
        if (bytes.length == 4) {
            long ip = Integer.toUnsignedLong(ByteBuffer.wrap(bytes).getInt());
            for (long[] block : new long[][]{{0x00000000L, 8}, {0x0a000000L, 8}, {0x64400000L, 10},
                    {0x7f000000L, 8}, {0xa9fe0000L, 16}, {0xac100000L, 12}, {0xc0000000L, 24},
                    {0xc0000200L, 24}, {0xc0586300L, 24}, {0xc0a80000L, 16}, {0xc6120000L, 15},
                    {0xc6336400L, 24}, {0xcb007100L, 24}, {0xe0000000L, 4}, {0xf0000000L, 4}}) {
                long mask = (0xffffffffL << (32 - block[1])) & 0xffffffffL;
                if ((ip & mask) == block[0]) {
                    return false;
                }
            }
            return true;
        }
        long prefix = Integer.toUnsignedLong(ByteBuffer.wrap(bytes).getInt());
        return (prefix & 0xe0000000L) == 0x20000000L
                && (prefix & 0xfffffe00L) != 0x20010000L && prefix != 0x20010db8L
                && (prefix & 0xffff0000L) != 0x20020000L && (prefix & 0xfffff000L) != 0x3fff0000L;
    }

    private Headers readHeaders(InputStream stream, Socket socket, long deadline) throws IOException {
        for (int interim = 0; interim < 5; interim++) {
            String statusLine = readLine(stream, socket, deadline, 4096);
            if (!statusLine.matches("HTTP/1\\.[01] [0-9]{3}(?: .*)?")) {
                throw invalid("图片服务器响应无效");
            }
            int status = Integer.parseInt(statusLine.substring(9, 12));
            Map<String, String> headers = new HashMap<>();
            int size = statusLine.length();
            String line;
            while (!(line = readLine(stream, socket, deadline, 8192)).isEmpty()) {
                size += line.length() + 2;
                int colon = line.indexOf(':');
                if (size > 65536 || colon < 1) {
                    throw invalid("图片服务器响应头无效");
                }
                String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                if (headers.put(name, value) != null
                        && List.of("content-length", "transfer-encoding", "location").contains(name)) {
                    throw invalid("图片服务器响应头重复");
                }
            }
            if (status >= 200) {
                return new Headers(status, headers);
            }
        }
        throw invalid("图片服务器响应无效");
    }

    private String readLine(InputStream stream, Socket socket, long deadline, int limit) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() <= limit) {
            socket.setSoTimeout(remainingMillis(deadline));
            int value = stream.read();
            if (value < 0) {
                throw new IOException("图片服务器连接中断");
            }
            if (value == '\n') {
                byte[] bytes = line.toByteArray();
                if (bytes.length == 0 || bytes[bytes.length - 1] != '\r') {
                    throw invalid("图片服务器行分隔无效");
                }
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
            }
            line.write(value);
        }
        throw invalid("图片服务器响应头过长");
    }

    private void readBody(InputStream stream, Socket socket, long deadline, ByteArrayOutputStream output,
            long count) throws IOException {
        if (count > MAX_IMAGE_BYTES - output.size()) {
            throw new BusinessException(413, "图片不能超过 20MB", null);
        }
        byte[] buffer = new byte[64 * 1024];
        long remaining = count;
        while (remaining != 0) {
            socket.setSoTimeout(remainingMillis(deadline));
            int length = stream.read(buffer, 0, remaining < 0 ? buffer.length
                    : (int) Math.min(buffer.length, remaining));
            if (length == -1) {
                if (remaining > 0) {
                    throw new IOException("图片下载不完整");
                }
                return;
            }
            if ((long) output.size() + length > MAX_IMAGE_BYTES) {
                throw new BusinessException(413, "图片不能超过 20MB", null);
            }
            output.write(buffer, 0, length);
            if (remaining > 0) {
                remaining -= length;
            }
        }
    }

    private record Headers(int status, Map<String, String> values) {
    }

    private URI remoteUri(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null || uri.getRawUserInfo() != null) {
                throw invalid("仅支持 HTTP(S) 或 data:image 地址");
            }
            return URI.create(uri.toASCIIString());
        } catch (URISyntaxException exception) {
            throw invalid("图片地址格式错误");
        }
    }

    private int remainingMillis(long deadline) {
        long remaining = (deadline - System.nanoTime()) / 1_000_000;
        if (remaining < 1) {
            throw invalid("图片下载超时");
        }
        return (int) Math.min(remaining, 30000);
    }

    private byte[] decodeData(String value) {
        int comma = value.indexOf(',');
        if (comma < 0) {
            throw invalid("图片 data URI 格式错误");
        }
        String header = value.substring(0, comma).toLowerCase(Locale.ROOT);
        if (!header.matches("data:image/[a-z0-9.+-]+(?:;[a-z0-9.+-]+=[a-z0-9.+-]+)*(?:;base64)?")) {
            throw invalid("图片 data URI 格式错误");
        }
        byte[] data = percentDecode(value.substring(comma + 1));
        if (!header.endsWith(";base64")) {
            return data;
        }
        try {
            return Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException exception) {
            throw invalid("图片 Base64 数据无效");
        }
    }

    private byte[] percentDecode(String value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '%') {
                if (index + 2 >= value.length()) {
                    throw invalid("图片 data URI 转义无效");
                }
                int high = Character.digit(value.charAt(++index), 16);
                int low = Character.digit(value.charAt(++index), 16);
                if (high < 0 || low < 0) {
                    throw invalid("图片 data URI 转义无效");
                }
                output.write(high * 16 + low);
            } else if (current <= 0x7f) {
                // data URI 中 '+' 是原始字节，不能按表单解码成空格。
                output.write(current);
            } else {
                throw invalid("图片 data URI 必须使用字节转义或 Base64");
            }
        }
        return output.toByteArray();
    }

    private String extension(byte[] bytes) {
        if (bytes.length >= 24 && matches(bytes, 0, 137, 80, 78, 71, 13, 10, 26, 10)
                && ascii(bytes, 12, "IHDR")) {
            return "png";
        }
        if (bytes.length >= 4 && matches(bytes, 0, 255, 216, 255)) {
            return "jpg";
        }
        if (bytes.length >= 13 && (ascii(bytes, 0, "GIF87a") || ascii(bytes, 0, "GIF89a"))) {
            return "gif";
        }
        if (bytes.length >= 16 && ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP")
                && (ascii(bytes, 12, "VP8 ") || ascii(bytes, 12, "VP8L") || ascii(bytes, 12, "VP8X"))) {
            return "webp";
        }
        if (bytes.length >= 26 && ascii(bytes, 0, "BM")) {
            return "bmp";
        }
        if (bytes.length >= 22 && matches(bytes, 0, 0, 0, 1, 0)
                && (Byte.toUnsignedInt(bytes[4]) | Byte.toUnsignedInt(bytes[5]) << 8) > 0) {
            return "ico";
        }
        if (bytes.length >= 16 && ascii(bytes, 4, "ftyp")) {
            long end = Integer.toUnsignedLong(ByteBuffer.wrap(bytes, 0, 4).getInt());
            if (end >= 16 && end <= bytes.length) {
                for (int offset = 8; offset + 4 <= end; offset += 4) {
                    if (offset != 12 && (ascii(bytes, offset, "avif") || ascii(bytes, offset, "avis"))) {
                        return "avif";
                    }
                }
            }
        }
        throw new BusinessException(415, "图片文件头不是受支持的格式", null);
    }

    private boolean ascii(byte[] bytes, int offset, String value) {
        return offset + value.length() <= bytes.length
                && new String(bytes, offset, value.length(), StandardCharsets.US_ASCII).equals(value);
    }

    private boolean matches(byte[] bytes, int offset, int... values) {
        if (offset + values.length > bytes.length) {
            return false;
        }
        for (int index = 0; index < values.length; index++) {
            if (Byte.toUnsignedInt(bytes[offset + index]) != values[index]) {
                return false;
            }
        }
        return true;
    }

    private BusinessException invalid(String message) {
        return new BusinessException(400, message, null);
    }

    public record Image(byte[] bytes, String mimeType, String extension) {
    }
}
