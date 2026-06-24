package org.speedrun.speedrun.webconfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WebConfigApplyResult {
    private final List<String> applied = new ArrayList<>();
    private final List<String> pendingReset = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public void applied(String message) {
        applied.add(message);
    }

    public void pendingReset(String message) {
        pendingReset.add(message);
    }

    public void warning(String message) {
        warnings.add(message);
    }

    public List<String> applied() {
        return Collections.unmodifiableList(applied);
    }

    public List<String> pendingReset() {
        return Collections.unmodifiableList(pendingReset);
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    public JSONObject toJson() {
        return new JSONObject()
                .put("applied", new JSONArray(applied))
                .put("pendingReset", new JSONArray(pendingReset))
                .put("warnings", new JSONArray(warnings));
    }
}
