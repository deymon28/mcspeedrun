package org.speedrun.speedrun.casualGameMode;

import org.speedrun.speedrun.Speedrun; // Import your main plugin class
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Collections;

public class GiveCompassCommand implements CommandExecutor {

    private final Speedrun plugin; // Reference to your main plugin instance

    public GiveCompassCommand(Speedrun plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by a player.");
            return true;
        }

        Player targetPlayer = (Player) sender;

        // Allow an admin to give the compass to another player
        if (args.length == 1) {
            if (!sender.hasPermission("speedrun.admin")) { // Assuming an admin permission
                sender.sendMessage(ChatColor.RED + "You don't have permission to give compass to others.");
                return true;
            }
            Player specifiedPlayer = Bukkit.getPlayer(args[0]);
            if (specifiedPlayer != null) {
                targetPlayer = specifiedPlayer;
            } else {
                sender.sendMessage(ChatColor.RED + "Player not found: " + args[0]);
                return true;
            }
        } else if (args.length > 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /givecompass [player]");
            return true;
        }

        // Create the special compass item
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta meta = compass.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "Navigation Compass");
            meta.setLore(Collections.singletonList(ChatColor.GRAY + "Right-click to open destinations menu."));
            compass.setItemMeta(meta);
        }

        targetPlayer.getInventory().addItem(compass);
        targetPlayer.sendMessage(ChatColor.GREEN + "You received a Navigation Compass!");
        if (!sender.equals(targetPlayer)) {
            sender.sendMessage(ChatColor.GREEN + "Gave a Navigation Compass to " + targetPlayer.getName() + ".");
        }
        return true;
    }
}
