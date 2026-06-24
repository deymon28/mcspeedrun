package org.speedrun.speedrun.webconfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WebConfigValidationResult {
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public void error(String message) {
        errors.add(message);
    }

    public void warning(String message) {
        warnings.add(message);
    }

    public boolean valid() {
        return errors.isEmpty();
    }

    public List<String> errors() {
        return Collections.unmodifiableList(errors);
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    public JSONObject toJson() {
        return new JSONObject()
                .put("valid", valid())
                .put("errors", new JSONArray(errors))
                .put("warnings", new JSONArray(warnings));
    }
}
