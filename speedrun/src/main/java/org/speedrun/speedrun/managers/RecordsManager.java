package org.speedrun.speedrun.managers;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.speedrun.speedrun.Speedrun;
import org.speedrun.speedrun.config.ConfigFileStore;
import org.speedrun.speedrun.utils.MessageUtil;
import org.speedrun.speedrun.utils.TimeUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Personal bests and stage splits, stored per game mode in {@code records.yml}.
 *
 * <p>A split is the run time at which a stage was completed. When a stage completes, the
 * split is compared with the best split for that stage; when the dragon dies, the total time
 * is compared with the best completion. Runs in which a stage was skipped with
 * {@code /run skipstage} are announced but never stored.</p>
 */
public class RecordsManager {
    private static final String FILE_NAME = "records.yml";

    private final Speedrun plugin;
    private final Path file;
    private YamlConfiguration records = new YamlConfiguration();
    private final Map<String, Long> currentSplits = new LinkedHashMap<>();
    private boolean eligible = true;
    private boolean readOnly;

    public RecordsManager(Speedrun plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve(FILE_NAME);
        load();
    }

    private void load() {
        if (!Files.exists(file)) {
            records = new YamlConfiguration();
            return;
        }
        try {
            records = ConfigFileStore.read(file);
        } catch (IOException | InvalidConfigurationException failure) {
            // Keep the damaged file instead of overwriting it with the next record.
            Path damaged = file.resolveSibling(FILE_NAME + ".damaged-" + System.currentTimeMillis());
            try {
                Files.move(file, damaged);
                plugin.getLogger().warning(FILE_NAME + " could not be read (" + failure.getMessage() + "). It was renamed to "
                        + damaged.getFileName() + " and records start fresh.");
            } catch (IOException moveFailure) {
                plugin.getLogger().warning(FILE_NAME + " could not be read or renamed; records are not saved this session.");
                readOnly = true;
            }
            records = new YamlConfiguration();
        }
    }

    private void save() {
        if (readOnly) {
            return;
        }
        try {
            ConfigFileStore.writeAtomically(file, records);
        } catch (IOException failure) {
            plugin.getLogger().warning("Could not save " + FILE_NAME + ": " + failure.getMessage());
        }
    }

    private String modeKey() {
        return plugin.getConfigManager().getGameMode().name();
    }

    /** Called when a new run starts. */
    public void startRun() {
        currentSplits.clear();
        eligible = true;
    }

    /** A skipped stage makes the run ineligible for records. */
    public void markIneligible() {
        eligible = false;
    }

    public boolean isEligible() {
        return eligible;
    }

    /** Records and announces a completed stage. */
    public void onStageCompleted(String stageKey, String stageName, long elapsedSeconds) {
        if (!plugin.getConfigManager().areRecordsEnabled()) {
            return;
        }
        currentSplits.put(stageKey, elapsedSeconds);
        String path = modeKey() + ".splits." + stageKey;
        long best = records.getLong(path, -1);
        if (plugin.getConfigManager().areSplitAnnouncementsEnabled()) {
            MessageUtil.broadcast(plugin.getConfigManager().getFormatted("records.split",
                    "%stage%", stageName,
                    "%time%", TimeUtil.format(elapsedSeconds),
                    "%delta%", delta(elapsedSeconds, best)));
        }
        if (eligible && (best < 0 || elapsedSeconds < best)) {
            records.set(path, elapsedSeconds);
            save();
        }
    }

    /** Compares a completed run with the best time and stores a new personal best. */
    public void onRunCompleted(long totalSeconds) {
        if (!plugin.getConfigManager().areRecordsEnabled()) {
            return;
        }
        String base = modeKey() + ".best";
        long best = records.getLong(base + ".time", -1);
        String deltaText = delta(totalSeconds, best);
        if (eligible && (best < 0 || totalSeconds < best)) {
            records.set(base + ".time", totalSeconds);
            records.set(base + ".date", LocalDate.now().toString());
            records.set(base + ".splits", new LinkedHashMap<>(currentSplits));
            records.set(modeKey() + ".completed-runs", records.getInt(modeKey() + ".completed-runs", 0) + 1);
            save();
            MessageUtil.broadcast(plugin.getConfigManager().getFormatted("records.new-best",
                    "%time%", TimeUtil.format(totalSeconds), "%delta%", deltaText));
            return;
        }
        if (eligible) {
            records.set(modeKey() + ".completed-runs", records.getInt(modeKey() + ".completed-runs", 0) + 1);
            save();
        }
        if (best >= 0) {
            MessageUtil.broadcast(plugin.getConfigManager().getFormatted("records.compare",
                    "%best%", TimeUtil.format(best), "%delta%", deltaText));
        }
    }

    /** Formats the difference to a best time, for example "(-00:42)" in green. */
    String delta(long seconds, long best) {
        if (best < 0) {
            return plugin.getConfigManager().getMessage("records.delta-first");
        }
        long difference = seconds - best;
        String formatted = TimeUtil.formatMinutesSeconds(Math.abs(difference));
        return plugin.getConfigManager().getMessage(difference <= 0 ? "records.delta-ahead" : "records.delta-behind")
                .replace("%delta%", formatted);
    }

    /** Shows the best time and best splits of the current game mode. */
    public void show(CommandSender sender) {
        ConfigManager config = plugin.getConfigManager();
        MessageUtil.send(sender, config.getFormatted("records.header", "%mode%", modeKey()));
        long best = records.getLong(modeKey() + ".best.time", -1);
        if (best < 0) {
            MessageUtil.send(sender, config.getFormatted("records.none"));
        } else {
            MessageUtil.send(sender, config.getFormatted("records.best",
                    "%time%", TimeUtil.format(best),
                    "%date%", records.getString(modeKey() + ".best.date", "?"),
                    "%runs%", String.valueOf(records.getInt(modeKey() + ".completed-runs", 1))));
        }
        ConfigurationSection splits = records.getConfigurationSection(modeKey() + ".splits");
        if (splits != null) {
            for (String stageKey : splits.getKeys(false)) {
                String stageName = config.getLangString("stages." + stageKey,
                        config.getRawConfig().getString("progression." + stageKey + ".display-name", stageKey));
                MessageUtil.send(sender, config.getFormatted("records.split-line",
                        "%stage%", stageName, "%time%", TimeUtil.format(splits.getLong(stageKey))));
            }
        }
    }

    /** Deletes all records of the current game mode. */
    public void reset() {
        records.set(modeKey(), null);
        save();
    }
}
