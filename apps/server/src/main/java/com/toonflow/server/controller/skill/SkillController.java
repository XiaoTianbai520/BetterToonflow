package com.toonflow.server.controller.skill;

import com.toonflow.server.common.ApiResponse;
import com.toonflow.server.service.skill.SkillService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SkillController {
    private final SkillService service;

    public SkillController(SkillService service) {
        this.service = service;
    }

    @GetMapping("/api/skills/get")
    public ApiResponse<List<SkillService.SkillSummary>> get() throws IOException {
        return ApiResponse.success(service.get());
    }

    @PostMapping("/api/skills/install")
    public ApiResponse<SkillService.SkillSummary> install(@Valid @RequestBody InstallRequest request) throws IOException {
        return new ApiResponse<>(200, service.install(request.fileName(), request.base64(), request.url(),
                Boolean.TRUE.equals(request.force())), "技能已安装");
    }

    @DeleteMapping("/api/skills/uninstall")
    public ApiResponse<Void> uninstall(@Valid @RequestBody NameRequest request) throws IOException {
        service.uninstall(request.name());
        return ApiResponse.success(null);
    }

    @PutMapping("/api/skills/setEnabled")
    public ApiResponse<Map<String, Object>> setEnabled(@Valid @RequestBody EnabledRequest request) throws IOException {
        return ApiResponse.success(service.setEnabled(request.name(), request.enabled()));
    }

    @GetMapping("/api/skills/list")
    public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "") String name) throws IOException {
        return ApiResponse.success(service.list(name));
    }

    @GetMapping("/api/skills/read")
    public ApiResponse<Map<String, String>> read(@RequestParam(defaultValue = "") String name,
            @RequestParam(required = false) String path) throws IOException {
        return ApiResponse.success(service.read(name, path));
    }

    @PutMapping("/api/skills/save")
    public ApiResponse<Void> save(@Valid @RequestBody SaveRequest request) throws IOException {
        service.save(request.name(), request.path(), request.content());
        return ApiResponse.success(null);
    }

    @PostMapping("/api/skills/create")
    public ApiResponse<Void> create(@Valid @RequestBody FileRequest request) throws IOException {
        service.create(request.name(), request.path());
        return ApiResponse.success(null);
    }

    @PutMapping("/api/skills/move")
    public ApiResponse<Void> move(@Valid @RequestBody MoveRequest request) throws IOException {
        service.move(request.name(), request.path(), request.target());
        return ApiResponse.success(null);
    }

    @PutMapping("/api/skills/order")
    public ApiResponse<Void> order(@Valid @RequestBody OrderRequest request) throws IOException {
        service.order(request.name(), request.order());
        return ApiResponse.success(null);
    }

    @GetMapping("/api/plugins/export")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String name) throws IOException {
        byte[] bytes = service.export(type, name);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + ".zip\"")
                .body(bytes);
    }

    public record InstallRequest(@Size(max = 128) String fileName, String base64,
            @Size(min = 1, max = 4096) String url, Boolean force) {
    }

    public record NameRequest(@NotBlank @Size(max = 1024) String name) {
    }

    public record EnabledRequest(@NotBlank @Size(max = 1024) String name, @NotNull Boolean enabled) {
    }

    public record FileRequest(@NotBlank @Size(max = 1024) String name,
            @NotBlank @Size(max = 1024) String path) {
    }

    public record SaveRequest(@NotBlank @Size(max = 1024) String name, @Size(min = 1, max = 1024) String path,
            @NotNull String content) {
    }

    public record MoveRequest(@NotBlank @Size(max = 1024) String name,
            @NotBlank @Size(max = 1024) String path, @NotBlank @Size(max = 1024) String target) {
    }

    public record OrderRequest(@NotBlank @Size(max = 1024) String name,
            @NotNull @Size(max = 2000) List<@NotBlank @Size(max = 1024) String> order) {
    }
}
