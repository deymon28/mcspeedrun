package org.speedrun.speedrun.managers;

import org.bukkit.entity.Player;
import org.speedrun.speedrun.Speedrun;
import org.speedrun.speedrun.config.ConfigMigrationReport;
import org.speedrun.speedrun.utils.MessageUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One-time join notices for administrators: first installation, configuration upgrades with
 * reset settings, and configuration errors. Each administrator sees a notice once per server
 * session.
 */
public class AdminNotices {
    private static final int MAX_LISTED_PATHS = 5;

    private final Speedrun plugin;
    private final Set<UUID> notified = new HashSet<>();

    public AdminNotices(Speedrun plugin) {
        this.plugin = plugin;
    }

    public void onJoin(Player player) {
        ConfigManager config = plugin.getConfigManager();
        if (!player.hasPermission("speedrun.admin") || !config.isAdminJoinNotificationEnabled()
                || !notified.add(player.getUniqueId())) {
            return;
        }
        ConfigMigrationReport report = config.getMigrationReport();
        if (report == null) {
            return;
        }
        switch (report.status()) {
            case "CREATED" -> MessageUtil.send(player, config.getFormatted("admin.installed"));
            case "MIGRATED" -> {
                MessageUtil.send(player, config.getFormatted("admin.upgraded",
                        "%from%", report.fromPluginVersion(),
                        "%to%", report.toPluginVersion(),
                        "%transferred%", String.valueOf(report.transferred()),
                        "%new%", String.valueOf(report.count("DEFAULTED")),
                        "%reset%", String.valueOf(report.count("RESET"))));
                if (report.count("RESET") > 0) {
                    MessageUtil.send(player, config.getFormatted("admin.reset-settings",
                            "%settings%", listPaths(report, "RESET")));
                }
                if (report.backupFile() != null) {
                    MessageUtil.send(player, config.getFormatted("admin.backup", "%file%", report.backupFile()));
                }
            }
            case "FAILED" -> MessageUtil.send(player, config.getFormatted("admin.config-failed"));
            default -> {
                if (report.count("INVALID") > 0) {
                    MessageUtil.send(player, config.getFormatted("admin.invalid-settings",
                            "%settings%", listPaths(report, "INVALID")));
                }
            }
        }
    }

    private static String listPaths(ConfigMigrationReport report, String action) {
        List<String> paths = report.changes().stream()
                .filter(change -> change.action().equals(action))
                .map(ConfigMigrationReport.Change::path)
                .toList();
        String shown = paths.stream().limit(MAX_LISTED_PATHS).collect(Collectors.joining(", "));
        return paths.size() > MAX_LISTED_PATHS ? shown + " (+" + (paths.size() - MAX_LISTED_PATHS) + ")" : shown;
    }

    /** Forgets who was notified, for example after /run reload produced a new report. */
    public void clear() {
        notified.clear();
    }
}
