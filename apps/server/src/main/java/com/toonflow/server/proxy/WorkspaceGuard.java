package com.toonflow.server.proxy;

import com.toonflow.server.common.BusinessException;
import com.toonflow.server.common.PathSandbox;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 网关侧工作区校验：只确认目录真实存在且在授权范围内，业务校验仍由内部运行时执行。 */
@Component
public class WorkspaceGuard {
    private static final int MAX_LENGTH = 4096;
    private final PathSandbox sandbox;

    public WorkspaceGuard(PathSandbox sandbox) {
        this.sandbox = sandbox;
    }

    public Path require(String directory) {
        if (directory == null || directory.isBlank() || directory.length() > MAX_LENGTH) {
            throw new BusinessException(400, "参数错误", null);
        }
        Path path;
        try {
            path = Path.of(directory);
        } catch (InvalidPathException exception) {
            throw new BusinessException(400, "参数错误", null);
        }
        try {
            return sandbox.root(path);
        } catch (NotDirectoryException exception) {
            throw new BusinessException(404, "目标不是目录", Map.of("code", "ENOTDIR"));
        } catch (NoSuchFileException exception) {
            throw new BusinessException(404, "目标工作区不存在", Map.of("code", "ENOENT"));
        } catch (AccessDeniedException exception) {
            throw new BusinessException(403, "无权访问该路径", Map.of("code", "EACCES"));
        } catch (IOException exception) {
            throw new BusinessException(404, "目标工作区不存在", Map.of("code", "ENOENT"));
        }
    }
}
