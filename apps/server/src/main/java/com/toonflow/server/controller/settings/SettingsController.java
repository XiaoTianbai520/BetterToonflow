package com.toonflow.server.controller.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.toonflow.server.common.ApiResponse;
import com.toonflow.server.service.settings.PersonalizationService;
import com.toonflow.server.service.settings.SettingsService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    private final SettingsService settingsService;
    private final PersonalizationService personalizationService;

    public SettingsController(SettingsService settingsService, PersonalizationService personalizationService) {
        this.settingsService = settingsService;
        this.personalizationService = personalizationService;
    }

    @GetMapping("/get")
    public ApiResponse<JsonNode> get() {
        return ApiResponse.success(settingsService.get());
    }

    @PutMapping("/save")
    public ApiResponse<JsonNode> save(@RequestBody JsonNode body) {
        return new ApiResponse<>(200, settingsService.save(body), "设置已保存");
    }

    @GetMapping("/systemPrompt")
    public ApiResponse<Map<String, Object>> systemPrompt() {
        return ApiResponse.success(settingsService.systemPrompt());
    }

    @GetMapping("/personalization/get")
    public ApiResponse<PersonalizationService.DocumentSnapshot> getPersonalization(
            @RequestParam(required = false) String document) {
        return ApiResponse.success(personalizationService.get(document));
    }

    @PutMapping("/personalization/save")
    public ApiResponse<PersonalizationService.DocumentSnapshot> savePersonalization(@RequestBody JsonNode body) {
        return new ApiResponse<>(200, personalizationService.save(body), "保存成功");
    }
}
