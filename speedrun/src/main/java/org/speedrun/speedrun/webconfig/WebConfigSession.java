package org.speedrun.speedrun.webconfig;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.json.JSONArray;
import org.json.JSONObject;
import org.speedrun.speedrun.config.ConfigFileStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Configuration transaction boundary of the web editor, independent of HTTP and scheduling.
 * All methods run on the server thread.
 *
 * <p>Three states are tracked: the editor's unsaved draft (client side), the saved file, and
 * the active runtime configuration. A revision token implements optimistic concurrency: a
 * change based on an outdated view is rejected so concurrent edits (another browser tab,
 * a manual file edit, {@code /run reload}) are never silently overwritten.</p>
 *
 * <p>Saving always happens before anything is applied, so a failed save cannot change the
 * running game. Settings marked {@code STARTUP_ONLY} are never applied live; settings marked
 * {@code RUN_SENSITIVE} are saved but kept inactive while a run is in progress.</p>
 */
final class WebConfigSession {
    enum Mode { VALIDATE, SAVE, APPLY, SAVE_APPLY }

    interface Access {
        FileConfiguration active();
        YamlConfiguration saved() throws Exception;
        YamlConfiguration defaults();
        void save(FileConfiguration candidate) throws Exception;
        void replaceActive(FileConfiguration candidate);
        WebConfigApplyResult apply(JSONObject changes);
        boolean runActive();
        boolean consoleRewardCommandsAllowed();
        JSONObject migration();
        JSONObject metadata();
    }

    record Reply(int status, JSONObject body) {
    }

    private record Observation(YamlConfiguration active, YamlConfiguration saved, boolean runActive) {
    }

    private final Access access;
    private String revision = UUID.randomUUID().toString();
    private JSONObject observedState;
    private boolean reloadRequired;

    WebConfigSession(Access access) {
        this.access = access;
    }

    JSONObject snapshot() throws Exception {
        return document(observe()).put("ok", true);
    }

    boolean reloadRequired() {
        return reloadRequired;
    }

    /** Clears the recovery flag, for example after {@code /run reload}. */
    void markRecovered() {
        reloadRequired = false;
    }

    Reply mutate(JSONObject changes, String expectedRevision, Mode mode) throws Exception {
        Observation before = observe();
        if (expectedRevision == null || !revision.equals(expectedRevision)) {
            return reply(409, before, "conflict",
                    "The configuration changed in the meantime. Reload the editor and review your edits.");
        }

        WebConfigValidationResult validation = WebConfigSchema.validatePatch(changes);
        if (!validation.valid()) {
            return new Reply(422, document(before).put("ok", false).put("validation", validation.toJson()));
        }
        YamlConfiguration desired = WebConfigSchema.patchedCopy(before.saved(), changes);
        WebConfigValidationResult fullValidation = WebConfigSchema.validateConfiguration(desired);
        fullValidation.errors().forEach(validation::error);
        fullValidation.warnings().forEach(validation::warning);
        if (!access.consoleRewardCommandsAllowed()) {
            for (String line : addedConsoleRewardLines(before.saved(), desired)) {
                validation.error("Reward line \"" + abbreviate(line) + "\" would run a console command. Console command rewards "
                        + "can only be added in config.yml, or after enabling web-config.allow-console-reward-commands there.");
            }
        }
        if (!validation.valid() || mode == Mode.VALIDATE) {
            return new Reply(validation.valid() ? 200 : 422, document(before)
                    .put("ok", validation.valid()).put("validation", validation.toJson())
                    .put("draftValues", WebConfigSchema.currentValues(desired)));
        }

        // Save mode persists the draft; Apply mode applies the saved file plus the draft.
        JSONObject requestedChanges = differences(before.active(), desired);
        boolean saved = mode == Mode.SAVE || mode == Mode.SAVE_APPLY;
        if (saved) {
            access.save(desired);
        }

        JSONObject fieldStates = new JSONObject();
        List<String> deferred = new ArrayList<>();
        WebConfigApplyResult applied = new WebConfigApplyResult();
        boolean runtimeApplied = false;

        if (mode == Mode.SAVE) {
            for (String path : requestedChanges.keySet()) {
                fieldStates.put(path, "saved");
            }
        } else if (reloadRequired) {
            requestedChanges.keySet().forEach(path -> fieldStates.put(path, saved ? "saved" : "not-applied"));
            applied.warning("A previous apply did not finish. Stop the run and use /run reload before applying more changes.");
        } else {
            YamlConfiguration nextActive = ConfigFileStore.copy(desired);
            for (String path : requestedChanges.keySet()) {
                String hold = holdReason(path, before.runActive());
                if (hold != null) {
                    restore(nextActive, before.active(), path);
                    deferred.add(path);
                    fieldStates.put(path, hold);
                }
            }
            JSONObject runtimeChanges = differences(before.active(), nextActive);
            if (!runtimeChanges.isEmpty()) {
                access.replaceActive(nextActive);
                try {
                    applied = access.apply(runtimeChanges);
                    runtimeApplied = true;
                    for (String path : runtimeChanges.keySet()) {
                        fieldStates.put(path, saved || !requestedFromDraft(changes, path) ? "applied" : "applied-unsaved");
                    }
                } catch (RuntimeException failure) {
                    // Managers can already have changed markers or scheduled work; claiming a rollback would be false.
                    reloadRequired = true;
                    runtimeChanges.keySet().forEach(path -> fieldStates.put(path, "unknown"));
                    revision = UUID.randomUUID().toString();
                    return new Reply(500, document(observe()).put("ok", false)
                            .put("errorCode", "partial-apply")
                            .put("error", "Some changes may have been applied. Stop the run and use /run reload to recover.")
                            .put("saved", saved).put("applied", false).put("partial", true)
                            .put("fieldStates", fieldStates).put("failureType", failure.getClass().getSimpleName()));
                }
            }
            if (deferred.stream().anyMatch(path -> "pending-restart".equals(fieldStates.optString(path)))) {
                applied.pendingReset("Some changes take effect after /run reload or a server restart.");
            }
            if (deferred.stream().anyMatch(path -> "pending-run-end".equals(fieldStates.optString(path)))) {
                applied.pendingReset("Some changes affect the rules of the current run. Apply them again after /run reset or /run stop.");
            }
        }

        revision = UUID.randomUUID().toString();
        return new Reply(200, document(observe()).put("ok", true).put("saved", saved)
                .put("applied", runtimeApplied).put("deferred", new JSONArray(deferred))
                .put("fieldStates", fieldStates).put("validation", validation.toJson())
                .put("apply", applied.toJson()));
    }

    /** Why a change cannot be applied now, or null when it can. */
    static String holdReason(String path, boolean runActive) {
        if (startupOnly(path)) {
            return "pending-restart";
        }
        if (runActive && runSensitive(path)) {
            return "pending-run-end";
        }
        return null;
    }

    private static boolean requestedFromDraft(JSONObject changes, String path) {
        if (changes.has(path)) {
            return true;
        }
        for (String key : changes.keySet()) {
            if (path.startsWith(key + ".") || key.startsWith(path + ".")) {
                return true;
            }
        }
        return false;
    }

    private static void restore(YamlConfiguration target, FileConfiguration source, String path) {
        Object value = source.get(path);
        target.set(path, value instanceof org.bukkit.configuration.ConfigurationSection section
                ? WebConfigSchema.jsonToConfigValue(WebConfigSchema.sectionToJson(section))
                : ConfigFileStore.copyValue(value));
    }

    /** Reward lines that would run console commands and are not already present in the saved file. */
    static List<String> addedConsoleRewardLines(FileConfiguration saved, FileConfiguration desired) {
        List<String> added = new ArrayList<>();
        for (String key : List.of("rewards.on-task-complete", "rewards.on-stage-complete")) {
            Set<String> existing = new HashSet<>(saved.getStringList(key));
            for (String line : desired.getStringList(key)) {
                if (!line.trim().startsWith("api:") && !existing.contains(line)) {
                    added.add(line);
                }
            }
        }
        return added;
    }

    private static String abbreviate(String text) {
        return text.length() > 60 ? text.substring(0, 57) + "..." : text;
    }

    private Reply reply(int status, Observation observation, String code, String error) {
        return new Reply(status, document(observation).put("ok", false).put("errorCode", code).put("error", error));
    }

    private Observation observe() throws Exception {
        Observation observation = new Observation(ConfigFileStore.copy(access.active()), access.saved(), access.runActive());
        JSONObject state = new JSONObject().put("active", WebConfigSchema.configFileToJson(observation.active()))
                .put("saved", WebConfigSchema.configFileToJson(observation.saved()))
                .put("runActive", observation.runActive()).put("reloadRequired", reloadRequired);
        if (observedState == null || !observedState.similar(state)) {
            revision = UUID.randomUUID().toString();
            observedState = state;
        }
        return observation;
    }

    private JSONObject document(Observation observation) {
        return new JSONObject().put("schema", WebConfigSchema.fieldsJson())
                .put("values", WebConfigSchema.currentValues(observation.active()))
                .put("savedValues", WebConfigSchema.currentValues(observation.saved()))
                .put("defaults", WebConfigSchema.currentValues(access.defaults()))
                .put("revision", revision).put("migration", access.migration()).put("metadata", access.metadata())
                .put("runActive", observation.runActive()).put("reloadRequired", reloadRequired)
                .put("pendingChanges", new JSONArray(differences(observation.active(), observation.saved()).keySet()));
    }

    static JSONObject differences(FileConfiguration before, FileConfiguration after) {
        JSONObject oldValues = WebConfigSchema.currentValues(before);
        JSONObject newValues = WebConfigSchema.currentValues(after);
        JSONObject changes = new JSONObject();
        boolean progressionChanged = !withoutSettings(oldValues.optJSONObject("progression"))
                .similar(withoutSettings(newValues.optJSONObject("progression")));
        boolean rewardsChanged = !equal(oldValues.opt("rewards"), newValues.opt("rewards"));
        for (String path : newValues.keySet()) {
            if ("progression".equals(path)) {
                if (progressionChanged) changes.put(path, newValues.get(path));
            } else if ("rewards".equals(path)) {
                if (rewardsChanged) changes.put(path, newValues.get(path));
            } else if (!(progressionChanged && path.startsWith("progression."))
                    && !(rewardsChanged && path.startsWith("rewards."))
                    && !equal(oldValues.opt(path), newValues.opt(path))) {
                changes.put(path, newValues.get(path));
            }
        }
        return changes;
    }

    static boolean runSensitive(String path) {
        if ("progression".equals(path)) {
            return true;
        }
        WebConfigField field = WebConfigSchema.field(path);
        return field != null && field.impact() == WebConfigField.ApplyImpact.RUN_SENSITIVE;
    }

    static boolean startupOnly(String path) {
        WebConfigField field = WebConfigSchema.field(path);
        return field != null && field.impact() == WebConfigField.ApplyImpact.STARTUP_ONLY;
    }

    private static JSONObject withoutSettings(JSONObject source) {
        JSONObject copy = source == null ? new JSONObject() : new JSONObject(source.toString());
        copy.remove("settings");
        return copy;
    }

    private static boolean equal(Object before, Object after) {
        return new JSONObject().put("value", before == null ? JSONObject.NULL : before)
                .similar(new JSONObject().put("value", after == null ? JSONObject.NULL : after));
    }
}
