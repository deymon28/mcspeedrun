package org.speedrun.speedrun.webconfig;

import org.bukkit.Bukkit;
import org.json.JSONObject;
import org.speedrun.speedrun.Speedrun;
import org.speedrun.speedrun.casualGameMode.CasualHighlightManager;

/**
 * Applies already-validated config changes to live plugin systems.
 */
public class RuntimeConfigApplier {
    private final Speedrun plugin;

    public RuntimeConfigApplier(Speedrun plugin) {
        this.plugin = plugin;
    }

    public WebConfigApplyResult apply(JSONObject patch) {
        WebConfigApplyResult result = new WebConfigApplyResult();

        plugin.getConfigManager().reloadLanguageFromCurrentConfig();
        plugin.getTraceLogger().reload();
        result.applied("Configuration values and language files refreshed.");
        result.applied("Trace diagnostics settings refreshed.");

        refreshCasualMode(result);
        refreshTaskState(patch, result);
        refreshRuntimeTasks(patch, result);
        refreshTabCoordinates(result);
        refreshScoreboards(result);
        reportDeferredImpacts(patch, result);

        for (WebConfigField.ApplyImpact impact : WebConfigSchema.impactsForPatch(patch)) {
            plugin.getLogger().info("Web config apply impact: " + impact);
        }

        return result;
    }

    private void refreshCasualMode(WebConfigApplyResult result) {
        if (plugin.getCasualGameModeManager() == null) {
            return;
        }

        boolean shouldBeActive = plugin.getConfigManager().isCasualGameModeEnabled();
        boolean active = plugin.getCasualGameModeManager().isCasualModeActive();

        if (shouldBeActive && !active) {
            plugin.getCasualGameModeManager().enable();
            result.applied("Casual mode components enabled.");
        } else if (!shouldBeActive && active) {
            plugin.getCasualGameModeManager().disable();
            plugin.getGameManager().getCasualModeStructureManager().clearWaypoints();
            result.applied("Casual mode components disabled and world markers cleared.");
        }
    }

    private void refreshTaskState(JSONObject patch, WebConfigApplyResult result) {
        boolean progressionChanged = patch.has("progression");
        boolean taskRulesChanged = patch.has("settings.task-tracking-mode")
                || patch.has("settings.scale-resources-by-playercount.enabled")
                || patch.has("settings.scale-resources-by-playercount.multiplier");

        if (!progressionChanged && !taskRulesChanged) {
            return;
        }

        if (plugin.getGameManager().isRunning()) {
            result.pendingReset("Progression and task tracking changes are saved, but active task progress is kept until /run reset.");
            return;
        }

        plugin.getTaskManager().reloadTasks();
        result.applied("Progression and task tracking rules reloaded.");
    }

    private void refreshRuntimeTasks(JSONObject patch, WebConfigApplyResult result) {
        boolean scannerChanged = patch.keySet().stream().anyMatch(key ->
                key.startsWith("casual.start-pre-scan.")
                        || key.startsWith("settings.proximity-scanner.")
                        || key.startsWith("settings.gamemode")
                        || key.startsWith("casual.enabled"));
        boolean waypointChanged = patch.keySet().stream().anyMatch(key -> key.startsWith("casual.structure_waypoints."));
        boolean highlightChanged = patch.keySet().stream().anyMatch(key -> key.startsWith("casual.nether_gold_highlight."));

        if (waypointChanged) {
            plugin.getGameManager().getCasualModeStructureManager().clearWaypoints();
            result.applied("Casual waypoints cleared so new waypoint settings are used for future markers.");
        }

        if (highlightChanged && plugin.getCasualGameModeManager() != null
                && plugin.getCasualGameModeManager().isCasualModeActive()) {
            CasualHighlightManager highlightManager = plugin.getCasualGameModeManager().getCasualHighlightManager();
            if (highlightManager != null) {
                highlightManager.reset();
                result.applied("Nether gold highlight task restarted.");
            }
        }

        if (scannerChanged || waypointChanged) {
            plugin.getGameManager().refreshRuntimeConfig();
            result.applied("Runtime scanner and pre-scan tasks refreshed where applicable.");
        }
    }

    private void refreshTabCoordinates(WebConfigApplyResult result) {
        plugin.getTabCoordinateDisplay().disable();
        plugin.getTabCoordinateDisplay().enable();
        result.applied("Tab coordinate display refreshed.");
    }

    private void refreshScoreboards(WebConfigApplyResult result) {
        Bukkit.getOnlinePlayers().forEach(player -> plugin.getScoreboardManager().updateScoreboard(player));
        result.applied("Online scoreboards refreshed.");
    }

    private void reportDeferredImpacts(JSONObject patch, WebConfigApplyResult result) {
        boolean startupOnly = WebConfigSchema.impactsForPatch(patch).contains(WebConfigField.ApplyImpact.STARTUP_ONLY);
        if (startupOnly) {
            result.pendingReset("Startup-only changes were saved in config.yml. Use /run reload, /run webconfig restart, or restart the server to activate them.");
        }
    }
}
