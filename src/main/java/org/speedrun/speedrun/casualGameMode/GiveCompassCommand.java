package org.speedrun.speedrun.casualGameMode;

import org.jetbrains.annotations.NotNull;
import org.speedrun.speedrun.Speedrun;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.speedrun.speedrun.utils.MessageUtil;

/**
 * Command handler for issuing the casual-mode navigation compass.
 */
public class GiveCompassCommand implements CommandExecutor {

    private final Speedrun plugin;

    public GiveCompassCommand(Speedrun plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player)) {
            MessageUtil.send(sender, plugin.getConfigManager().getFormattedText("commands.player-only"));
            return true;
        }

        Player targetPlayer = (Player) sender;

        if (args.length == 1) {
            if (!sender.hasPermission("speedrun.admin")) {
                MessageUtil.send(sender, plugin.getConfigManager().getFormattedText("commands.givecompass.no-permission-others"));
                return true;
            }
            Player specifiedPlayer = Bukkit.getPlayer(args[0]);
            if (specifiedPlayer != null) {
                targetPlayer = specifiedPlayer;
            } else {
                MessageUtil.send(sender, plugin.getConfigManager().getFormattedText("commands.givecompass.player-not-found", "%player%", args[0]));
                return true;
            }
        } else if (args.length > 1) {
            MessageUtil.send(sender, plugin.getConfigManager().getFormattedText("commands.givecompass.usage"));
            return true;
        }

        ItemStack compass = plugin.getCasualGameModeManager() != null
                && plugin.getCasualGameModeManager().getCompassListener() != null
                ? plugin.getCasualGameModeManager().getCompassListener().createNavigationCompass(null, null)
                : createLegacyCompass();

        targetPlayer.getInventory().addItem(compass);
        MessageUtil.send(targetPlayer, plugin.getConfigManager().getFormatted("commands.givecompass.received"));
        if (!sender.equals(targetPlayer)) {
            MessageUtil.send(sender, plugin.getConfigManager().getFormattedText("commands.givecompass.gave", "%player%", targetPlayer.getName()));
        }
        return true;
    }

    private ItemStack createLegacyCompass() {
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta meta = compass.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.getConfigManager().getFormattedText("items.navigation-compass.name"));
            meta.setLore(plugin.getConfigManager().getFormattedTextList("items.navigation-compass.lore"));
            compass.setItemMeta(meta);
        }
        return compass;
    }
}
