package com.toonflow.server.controller.asset;

import com.toonflow.server.common.ApiResponse;
import com.toonflow.server.common.BusinessException;
import com.toonflow.server.service.asset.AssetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assets")
public class AssetController {
    private final AssetService service;

    public AssetController(AssetService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public ApiResponse<Map<String, List<AssetService.AssetEntry>>> list() throws IOException {
        return ApiResponse.success(service.list());
    }

    @GetMapping("/read")
    public ResponseEntity<Resource> read(@RequestParam(defaultValue = "") String path,
            @RequestParam(required = false) String download) throws IOException {
        if (download != null && !download.equals("true")) {
            throw new BusinessException(400, "download 仅允许为 true", null);
        }
        Path target = service.read(path);
        Resource resource = new FileSystemResource(target);
        var response = ResponseEntity.ok().contentType(MediaTypeFactory.getMediaType(resource)
                .orElse(MediaType.APPLICATION_OCTET_STREAM));
        if (download != null) {
            response.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(target.getFileName().toString(), StandardCharsets.UTF_8).build().toString());
        }
        // Spring MVC 的 Resource 转换器负责单段/多段 Range 与 416 响应。
        return response.body(resource);
    }

    @PutMapping(value = "/save", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ApiResponse<Void> save(@RequestParam(defaultValue = "") String path, HttpServletRequest request)
            throws IOException {
        service.save(path, request.getInputStream(), request.getContentLengthLong());
        return ApiResponse.success(null);
    }

    @PostMapping("/mkdir")
    public ApiResponse<Void> mkdir(@Valid @RequestBody PathRequest request) throws IOException {
        service.mkdir(request.path());
        return ApiResponse.success(null);
    }

    @PostMapping("/rename")
    public ApiResponse<Void> rename(@Valid @RequestBody RenameRequest request) throws IOException {
        service.rename(request.path(), request.target());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/remove")
    public ApiResponse<Void> remove(@Valid @RequestBody PathRequest request) throws IOException {
        service.remove(request.path());
        return ApiResponse.success(null);
    }

    public record PathRequest(@NotBlank @Size(max = 4096) String path) {
    }

    public record RenameRequest(@NotBlank @Size(max = 4096) String path,
            @NotBlank @Size(max = 4096) String target) {
    }
}
