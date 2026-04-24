package com.betterlogin.paper.command;

import com.betterlogin.paper.BetterLoginBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Paper-side {@code /betterlogintest} (alias {@code /blt}) command for admins.
 *
 * <h2>Subcommands</h2>
 * <pre>
 *   /betterlogintest status           – show plugin state and pending-auth players
 *   /betterlogintest debug [on|off]   – toggle verbose debug logging at runtime
 *   /betterlogintest resend <player>  – re-send AUTH_REQUIRED to a stuck player
 * </pre>
 */
public class BetterLoginTestCommand implements CommandExecutor, TabCompleter {

    private final BetterLoginBridge plugin;

    public BetterLoginTestCommand(BetterLoginBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("betterlogin.admin")) {
            sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "status" -> handleStatus(sender);
            case "debug"  -> handleDebug(sender, args);
            case "resend" -> handleResend(sender, args);
            default       -> sendHelp(sender, label);
        }
        return true;
    }

    // ------------------------------------------------------------------

    private void handleStatus(CommandSender sender) {
        Set<UUID> pending = plugin.getPendingAuth();

        sender.sendMessage(Component.text("=== BetterLogin Bridge Status ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Channel: ", NamedTextColor.GRAY)
                .append(Component.text(BetterLoginBridge.CHANNEL, NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Debug mode: ", NamedTextColor.GRAY)
                .append(Component.text(
                        plugin.getConfig().getBoolean("debug", false) ? "ON" : "OFF",
                        plugin.getConfig().getBoolean("debug", false) ? NamedTextColor.GREEN : NamedTextColor.YELLOW)));

        sender.sendMessage(Component.text("Players in auth dialog (" + pending.size() + "):", NamedTextColor.GRAY));
        if (pending.isEmpty()) {
            sender.sendMessage(Component.text("  (none)", NamedTextColor.DARK_GRAY));
        } else {
            for (UUID uuid : pending) {
                Player p = plugin.getServer().getPlayer(uuid);
                String name = p != null ? p.getName() : uuid.toString();
                sender.sendMessage(Component.text("  " + name, NamedTextColor.YELLOW));
            }
        }

        // Show all online players and whether they are frozen
        sender.sendMessage(Component.text("All online players:", NamedTextColor.GRAY));
        if (plugin.getServer().getOnlinePlayers().isEmpty()) {
            sender.sendMessage(Component.text("  (none)", NamedTextColor.DARK_GRAY));
        } else {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                boolean frozen = pending.contains(p.getUniqueId());
                sender.sendMessage(Component.text("  " + p.getName() + " -> ", NamedTextColor.WHITE)
                        .append(Component.text(frozen ? "PENDING AUTH" : "authenticated",
                                frozen ? NamedTextColor.YELLOW : NamedTextColor.GREEN)));
            }
        }
    }

    private void handleDebug(CommandSender sender, String[] args) {
        boolean enable;
        if (args.length >= 2) {
            enable = args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true");
        } else {
            enable = !plugin.getConfig().getBoolean("debug", false);
        }
        plugin.getConfig().set("debug", enable);
        sender.sendMessage(Component.text("Debug mode " + (enable ? "enabled" : "disabled") + ".",
                enable ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        plugin.getLogger().info("Debug mode " + (enable ? "enabled" : "disabled")
                + " by " + sender.getName());
    }

    private void handleResend(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /betterlogintest resend <player>", NamedTextColor.RED));
            return;
        }
        Player target = plugin.getServer().getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("Player not found: " + args[1], NamedTextColor.RED));
            return;
        }

        // Re-add to pending and show dialog
        plugin.getPendingAuth().add(target.getUniqueId());
        plugin.getServer().getScheduler().runTask(plugin, () ->
                plugin.getDialogHandler().showLoginDialog(target));

        sender.sendMessage(Component.text("Re-sent auth dialog to " + target.getName() + ".", NamedTextColor.GREEN));
        plugin.getLogger().info("Auth dialog re-sent to " + target.getName() + " by " + sender.getName());
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(Component.text("BetterLogin Bridge commands:", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  /" + label + " status           ", NamedTextColor.YELLOW)
                .append(Component.text("- show bridge status", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /" + label + " debug [on|off]   ", NamedTextColor.YELLOW)
                .append(Component.text("- toggle verbose debug logging", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /" + label + " resend <player>  ", NamedTextColor.YELLOW)
                .append(Component.text("- re-send auth dialog to a stuck player", NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("betterlogin.admin")) return List.of();
        if (args.length == 1) {
            return List.of("status", "debug", "resend");
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("debug")) return List.of("on", "off");
            if (args[0].equalsIgnoreCase("resend")) {
                return plugin.getServer().getOnlinePlayers()
                        .stream().map(Player::getName).toList();
            }
        }
        return List.of();
    }
}
