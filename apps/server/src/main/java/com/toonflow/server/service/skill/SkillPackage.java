package com.toonflow.server.service.skill;

import com.toonflow.server.common.BusinessException;
import com.toonflow.server.common.PathSandbox;
import com.toonflow.server.service.asset.SandboxedFiles;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/** 技能包的格式及资源边界校验，不承担数据库事务。 */
@Component
public class SkillPackage {
    public static final int MAX_BYTES = 20 * 1024 * 1024;
    public static final String ORDER_FILE = ".toonflowOrder.json";
    private final SandboxedFiles files;
    private final PathSandbox sandbox;

    public SkillPackage(SandboxedFiles files, PathSandbox sandbox) {
        this.files = files;
        this.sandbox = sandbox;
    }

    public static String utf8(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.indexOf('\0') >= 0) {
                throw invalid("文件必须是 UTF-8 文本");
            }
            return text;
        } catch (CharacterCodingException exception) {
            throw invalid("文件必须是 UTF-8 文本");
        }
    }

    public Manifest manifest(String content, String expectedName) {
        String text = content.startsWith("\ufeff") ? content.substring(1) : content;
        String[] lines = text.split("\\r?\\n", -1);
        if (lines.length < 3 || !lines[0].equals("---")) {
            throw invalid("SKILL.md 缺少 YAML Frontmatter");
        }
        StringBuilder header = new StringBuilder();
        int end = 1;
        while (end < lines.length && !lines[end].equals("---")) {
            header.append(lines[end++]).append('\n');
        }
        if (end == lines.length) {
            throw invalid("SKILL.md Frontmatter 未闭合");
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(32);
        options.setCodePointLimit(MAX_BYTES);
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(header.toString());
        } catch (YAMLException exception) {
            throw invalid("SKILL.md Frontmatter 格式非法");
        }
        if (!(loaded instanceof Map<?, ?> values)
                || !(values.get("name") instanceof String name)
                || !(values.get("description") instanceof String description)
                || !name.matches("[a-zA-Z0-9][a-zA-Z0-9-]{0,127}") || description.isBlank()
                || description.codePointCount(0, description.length()) > 1024
                || (expectedName != null && !name.equals(expectedName))) {
            throw invalid("Frontmatter name 必须与目录名一致，description 必填且不超过 1024 字");
        }
        Map<?, ?> metadata = values.get("metadata") instanceof Map<?, ?> value ? value : Map.of();
        return new Manifest(name, field(metadata, values, "version"), field(metadata, values, "displayName"),
                description, field(metadata, values, "author"), field(metadata, values, "github"));
    }

    private String field(Map<?, ?> metadata, Map<?, ?> values, String name) {
        Object value = metadata.containsKey(name) ? metadata.get(name) : values.get(name);
        return value == null ? "" : value instanceof String || value instanceof Number ? value.toString() : "";
    }

    public void extract(byte[] bytes, Path stage) throws IOException {
        ZipFile archive;
        try {
            archive = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(bytes)).get();
        } catch (IOException exception) {
            throw invalid("ZIP 包格式非法");
        }
        try (archive) {
            var entries = archive.getEntries();
            Set<String> names = new HashSet<>();
            Set<String> expandedPaths = new HashSet<>();
            int count = 0;
            int total = 0;
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (++count > 2000) {
                    throw new BusinessException(413, "技能包条目超过 2000 个", null);
                }
                int mode = entry.getUnixMode() & 0170000;
                if (entry.isUnixSymlink() || (mode != 0 && mode != 0100000 && mode != 0040000)
                        || !archive.canReadEntryData(entry)) {
                    throw invalid("ZIP 包不能包含符号链接、特殊文件或加密条目");
                }
                String path = entry.getName().replace('\\', '/');
                if (entry.isDirectory() && path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                Path target = files.resolve(stage, path, 1024);
                if (!names.add(path.toLowerCase(Locale.ROOT))) {
                    throw invalid("ZIP 包包含重复路径");
                }
                String prefix = "";
                String[] parts = path.split("/");
                for (int index = 0; index < parts.length; index++) {
                    prefix = prefix.isEmpty() ? parts[index] : prefix + "/" + parts[index];
                    if (parts[index].equalsIgnoreCase(ORDER_FILE)
                            && (index < parts.length - 1 || entry.isDirectory())) {
                        throw invalid("系统排序文件不能作为目录");
                    }
                    expandedPaths.add(prefix.toLowerCase(Locale.ROOT));
                    // 显式条目之外，隐式创建的父目录也计入上限，避免深层空目录解压炸弹。
                    if (expandedPaths.size() > 2000) {
                        throw new BusinessException(413, "技能包展开条目超过 2000 个", null);
                    }
                }
                if (entry.getSize() > MAX_BYTES - total) {
                    throw new BusinessException(413, "技能包解压总大小超过 20MB", null);
                }
                if (entry.isDirectory()) {
                    if (entry.getSize() > 0) {
                        throw invalid("ZIP 目录条目格式非法");
                    }
                    sandbox.createDirectories(stage, path);
                    continue;
                }
                byte[] content;
                try (InputStream input = archive.getInputStream(entry)) {
                    content = SandboxedFiles.readLimited(input, MAX_BYTES - total);
                } catch (IOException exception) {
                    throw invalid("ZIP 条目损坏");
                }
                CRC32 crc = new CRC32();
                crc.update(content);
                if (entry.getSize() != content.length || entry.getCrc() != crc.getValue()) {
                    throw invalid("ZIP 条目校验失败");
                }
                total += content.length;
                if (!target.getParent().equals(stage)) {
                    sandbox.createDirectories(stage, SandboxedFiles.relative(stage, target.getParent()));
                }
                // 排序元数据只由数据库及 order 接口管理，不信任安装包中的快照。
                if (!target.getFileName().toString().equalsIgnoreCase(ORDER_FILE)) {
                    files.create(stage, path, content);
                }
            }
        }
    }

    public Path packageRoot(Path stage) throws IOException {
        if (Files.isRegularFile(files.resolve(stage, "SKILL.md", 1024), LinkOption.NOFOLLOW_LINKS)) {
            return stage;
        }
        List<Path> children = files.children(stage, stage);
        if (children.size() == 1 && Files.isDirectory(children.getFirst(), LinkOption.NOFOLLOW_LINKS)) {
            Path directory = children.getFirst();
            if (Files.isRegularFile(files.resolve(directory, "SKILL.md", 1024), LinkOption.NOFOLLOW_LINKS)) {
                return directory;
            }
        }
        throw invalid("技能包缺少唯一的 SKILL.md 入口文件");
    }

    public Download download(String url) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw invalid("远程 URL 非法");
        }
        for (int redirect = 0; redirect <= 5; redirect++) {
            validateRemote(uri);
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(false);
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) {
                        throw invalid("远程下载重定向非法");
                    }
                    uri = uri.resolve(location);
                    continue;
                }
                if (status != 200) {
                    throw invalid("远程技能包下载失败");
                }
                if (connection.getContentLengthLong() > MAX_BYTES) {
                    throw new BusinessException(413, "技能包超过 20MB", null);
                }
                try (InputStream input = connection.getInputStream()) {
                    byte[] bytes = SandboxedFiles.readLimited(input, MAX_BYTES);
                    // 下载地址及重定向目标可能没有后缀（如 GitHub codeload），以包内容识别格式。
                    boolean zip = bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K';
                    return new Download(zip ? "skill.zip" : "SKILL.md", bytes);
                }
            } finally {
                connection.disconnect();
            }
        }
        throw invalid("远程下载重定向次数过多");
    }

    private void validateRemote(URI uri) throws IOException {
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw invalid("仅支持 HTTP/HTTPS 技能包 URL");
        }
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            byte[] bytes = address.getAddress();
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()
                    || (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc)) {
                throw invalid("远程 URL 不能指向本地或私有网络");
            }
        }
    }

    public static void validateExtension(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (fileName.isBlank() || fileName.length() > 128 || !(lower.endsWith(".zip") || lower.endsWith(".md"))) {
            throw invalid("仅支持 .zip 或 .md 技能包");
        }
    }

    public byte[] export(Path root) throws IOException {
        List<Path> entries = files.tree(root, ORDER_FILE);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int total = 0;
        try (ZipOutputStream archive = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Path entry : entries) {
                if (entry.getFileName().toString().equalsIgnoreCase(ORDER_FILE)) {
                    continue;
                }
                String path = SandboxedFiles.relative(root, entry);
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    archive.putNextEntry(new ZipEntry(path + "/"));
                } else {
                    byte[] content = files.read(root, path, 1024, MAX_BYTES - total);
                    total += content.length;
                    archive.putNextEntry(new ZipEntry(path));
                    archive.write(content);
                }
                archive.closeEntry();
            }
        }
        if (output.size() > MAX_BYTES) {
            throw new BusinessException(413, "导出技能包超过 20MB", null);
        }
        return output.toByteArray();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(400, message, null);
    }

    public record Manifest(String name, String version, String displayName, String description, String author,
            String github) {
        public SkillService.SkillSummary summary(boolean enabled) {
            return new SkillService.SkillSummary(name, version, displayName, description, author, github, enabled);
        }
    }

    public record Download(String fileName, byte[] bytes) {
    }
}
