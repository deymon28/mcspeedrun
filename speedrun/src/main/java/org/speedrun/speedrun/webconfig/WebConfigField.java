package org.speedrun.speedrun.webconfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Describes one editable config field exposed through the local web configurator.
 */
public record WebConfigField(
        String path,
        String section,
        String label,
        String description,
        FieldType type,
        ApplyImpact impact,
        DangerLevel danger,
        Object defaultValue,
        List<String> options,
        Double min,
        Double max) {

    public enum FieldType {
        BOOLEAN,
        INTEGER,
        DECIMAL,
        STRING,
        ENUM,
        STRING_LIST
    }

    public enum ApplyImpact {
        INSTANT,
        RESTART_TASK,
        RUN_SENSITIVE,
        STARTUP_ONLY
    }

    public enum DangerLevel {
        NONE,
        CAUTION,
        DANGEROUS
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject()
                .put("path", path)
                .put("section", section)
                .put("label", label)
                .put("description", description)
                .put("type", type.name())
                .put("impact", impact.name())
                .put("danger", danger.name())
                .put("defaultValue", defaultValue == null ? JSONObject.NULL : defaultValue);

        if (options != null && !options.isEmpty()) {
            json.put("options", new JSONArray(options));
        }
        if (min != null) {
            json.put("min", min);
        }
        if (max != null) {
            json.put("max", max);
        }
        return json;
    }
}
