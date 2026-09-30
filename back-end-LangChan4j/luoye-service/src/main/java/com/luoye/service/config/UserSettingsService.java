package com.luoye.service.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luoye.dao.entity.AppConfig;
import com.luoye.dao.mapper.AppConfigMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 读取/保存用户运行配置。 */
@Service
public class UserSettingsService {

    public static final String KEY_SETTINGS = "runtime";

    private final AppConfigMapper configs;
    private final ObjectMapper mapper;
    private final String defaultPersona;

    public UserSettingsService(
            AppConfigMapper configs,
            ObjectMapper mapper,
            @Value("${luoye.chat.persona:你是落叶，一位温暖、诚实、有独立判断的个人助手。用中文简洁回答，记不清时坦诚说明。}")
            String defaultPersona) {
        this.configs = configs;
        this.mapper = mapper;
        this.defaultPersona = defaultPersona;
    }

    public Map<String, Object> get(UUID owner) {
        Map<String, Object> settings = defaults();
        configs.selectOwned(owner).stream()
                .filter(row -> KEY_SETTINGS.equals(row.getConfigKey()))
                .findFirst()
                .ifPresent(row -> settings.putAll(asMap(row.getConfigValue())));
        return settings;
    }

    public Map<String, Object> save(UUID owner, Map<String, Object> patch) {
        Map<String, Object> current = get(owner);
        if (patch != null) {
            current.putAll(patch);
        }
        try {
            configs.upsert(UUID.randomUUID(), owner, KEY_SETTINGS, mapper.writeValueAsString(current));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return get(owner);
    }

    public boolean memoryEnabled(UUID owner) {
        return bool(get(owner).get("memoryEnabled"), true);
    }

    public boolean webEnabled(UUID owner) {
        return bool(get(owner).get("webEnabled"), false);
    }

    public String persona(UUID owner) {
        Object value = get(owner).get("persona");
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return defaultPersona;
    }

    public double companion(UUID owner) {
        Object value = get(owner).get("companion");
        if (value instanceof Number number) {
            return Math.max(0, Math.min(1, number.doubleValue()));
        }
        return 0.6;
    }

    private Map<String, Object> defaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("persona", defaultPersona);
        defaults.put("companion", 0.6);
        defaults.put("memoryEnabled", true);
        defaults.put("webEnabled", false);
        defaults.put("displayName", "");
        return defaults;
    }

    private Map<String, Object> asMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = mapper.readTree(json);
            return mapper.convertValue(node, mapper.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return fallback;
    }
}
