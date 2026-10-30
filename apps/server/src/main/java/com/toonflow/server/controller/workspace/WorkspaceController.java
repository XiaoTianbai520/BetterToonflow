package com.toonflow.server.controller.workspace;

import com.toonflow.server.common.ApiResponse;
import com.toonflow.server.common.BusinessException;
import com.toonflow.server.service.workspace.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {
    private final WorkspaceService workspace;

    public WorkspaceController(WorkspaceService workspace) {
        this.workspace = workspace;
    }

    @GetMapping("/check")
    public ApiResponse<?> check(@RequestParam(defaultValue = "") String directory) throws IOException {
        return ApiResponse.success(workspace.check(directory));
    }

    @GetMapping("/list")
    public ApiResponse<?> list(@RequestParam(defaultValue = "") String path) throws IOException {
        return ApiResponse.success(workspace.listWorkspaces(path));
    }

    @PostMapping("/selectDirectory")
    public ApiResponse<?> selectDirectory() throws IOException {
        return ApiResponse.success(workspace.selectDirectory());
    }

    @GetMapping("/files/list")
    public ApiResponse<?> listFiles(@RequestParam(defaultValue = "") String directory,
            @RequestParam(defaultValue = "") String path) throws IOException {
        return ApiResponse.success(workspace.listFiles(directory, path));
    }

    @GetMapping("/files/read")
    public Object read(@RequestParam(defaultValue = "") String directory,
            @RequestParam(defaultValue = "") String path,
            @RequestParam(required = false) String snapshot,
            @RequestParam(required = false) Integer maxBytes,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (snapshot != null && !snapshot.equals("true")) {
            throw new BusinessException(400, "snapshot 只能为 true", null);
        }
        if (snapshot != null) {
            return ApiResponse.success(workspace.readSnapshot(directory, path, maxBytes));
        }
        workspace.readBinary(directory, path, request.getHeader("Range"), response);
        return null;
    }

    @PutMapping(value = "/files/write", consumes = "application/octet-stream")
    public ApiResponse<?> write(@RequestParam(defaultValue = "") String directory,
            @RequestParam(defaultValue = "") String path,
            @RequestParam(required = false) String exclusive,
            @RequestParam(required = false) String expectedRevision,
            @RequestParam(required = false) String encoding, HttpServletRequest request) throws IOException {
        if (exclusive != null && !exclusive.equals("true") && !exclusive.equals("false")) {
            throw new BusinessException(400, "exclusive 只能为 true 或 false", null);
        }
        return ApiResponse.success(workspace.write(directory, path, request.getInputStream(),
                request.getContentLengthLong(), "true".equals(exclusive), expectedRevision, encoding));
    }

    @PostMapping("/files/mkdir")
    public ApiResponse<Void> mkdir(@Valid @RequestBody FileRequest request) throws IOException {
        workspace.mkdir(request.directory(), request.path());
        return ApiResponse.success(null);
    }

    @PostMapping("/files/rename")
    public ApiResponse<Void> rename(@Valid @RequestBody TransferRequest request) throws IOException {
        workspace.rename(request.directory(), request.path(), request.target());
        return ApiResponse.success(null);
    }

    @PostMapping("/files/copy")
    public ApiResponse<Void> copy(@Valid @RequestBody TransferRequest request) throws IOException {
        workspace.copy(request.directory(), request.path(), request.target());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/files/remove")
    public ApiResponse<Void> remove(@Valid @RequestBody RemoveRequest request) throws IOException {
        workspace.remove(request.directory(), request.path(), Boolean.TRUE.equals(request.recursive()));
        return ApiResponse.success(null);
    }

    @PostMapping("/files/importImage")
    public ApiResponse<?> importImage(@Valid @RequestBody ImageRequest request) throws IOException {
        return ApiResponse.success(workspace.importImage(request.directory(), request.url()));
    }

    public record FileRequest(@NotNull @Size(min = 1, max = 4096) String directory,
            @NotNull @Size(max = 4096) String path) {
    }

    public record TransferRequest(@NotNull @Size(min = 1, max = 4096) String directory,
            @NotNull @Size(max = 4096) String path,
            @NotNull @Size(min = 1, max = 4096) String target) {
    }

    public record RemoveRequest(@NotNull @Size(min = 1, max = 4096) String directory,
            @NotNull @Size(max = 4096) String path, Boolean recursive) {
    }

    public record ImageRequest(@NotNull @Size(min = 1, max = 4096) String directory,
            @NotNull @Size(min = 1, max = 16384) String url) {
    }
}
