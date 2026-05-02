package com.betterlogin.velocity.command;

import com.betterlogin.velocity.BetterLoginPlugin;
import com.betterlogin.velocity.auth.AuthManager;
import com.betterlogin.velocity.auth.AuthState;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Collection;
import java.util.List;

/**
 * Provides the {@code /betterlogin} proxy command for debug and admin tasks.
 *
 * <h2>Subcommands</h2>
 * <pre>
 *   /betterlogin status          – print plugin status and all online players' auth states
 *   /betterlogin debug [on|off]  – toggle (or force) verbose debug logging at runtime
 *   /betterlogin reload          – reload config.yml without restarting the proxy
 * </pre>
 */
public class BetterLoginCommand implements SimpleCommand {

    private final BetterLoginPlugin plugin;

    public BetterLoginCommand(BetterLoginPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            sendHelp(source);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "status" -> handleStatus(source);
            case "debug"  -> handleDebug(source, args);
            case "reload" -> handleReload(source);
            default       -> sendHelp(source);
        }
    }

    // ------------------------------------------------------------------

    private void handleStatus(CommandSource source) {
        AuthManager auth = plugin.getAuthManager();
        boolean pluginOk = auth != null;

        source.sendMessage(Component.text("=== BetterLogin Status ===", NamedTextColor.GOLD));
        source.sendMessage(Component.text("Plugin initialized: ", NamedTextColor.GRAY)
                .append(Component.text(pluginOk ? "YES" : "NO (initialization failed – check logs!)",
                        pluginOk ? NamedTextColor.GREEN : NamedTextColor.RED)));

        if (!pluginOk) {
            source.sendMessage(Component.text(
                    "The plugin failed to start. Check the proxy console for errors at startup.",
                    NamedTextColor.RED));
            return;
        }

        source.sendMessage(Component.text("Debug mode: ", NamedTextColor.GRAY)
                .append(Component.text(plugin.getConfig().isDebug() ? "ON" : "OFF",
                        plugin.getConfig().isDebug() ? NamedTextColor.GREEN : NamedTextColor.YELLOW)));
        source.sendMessage(Component.text("Main server: ", NamedTextColor.GRAY)
                .append(Component.text(plugin.getConfig().getMainServer(), NamedTextColor.WHITE)));

        String limbo = plugin.getConfig().getLimboServer();
        source.sendMessage(Component.text("Limbo server: ", NamedTextColor.GRAY)
                .append(Component.text(
                        (limbo == null || limbo.isBlank()) ? "(none)" : limbo,
                        NamedTextColor.WHITE)));

        Collection<Player> online = plugin.getProxy().getAllPlayers();
        source.sendMessage(Component.text("Online players (" + online.size() + "):", NamedTextColor.GRAY));

        if (online.isEmpty()) {
            source.sendMessage(Component.text("  (none)", NamedTextColor.DARK_GRAY));
        } else {
            for (Player p : online) {
                AuthState state = auth.getState(p.getUniqueId());
                NamedTextColor color = switch (state) {
                    case AUTHENTICATED, PREMIUM -> NamedTextColor.GREEN;
                    case PENDING_DIALOG -> NamedTextColor.YELLOW;
                    default -> NamedTextColor.GRAY;
                };
                source.sendMessage(Component.text("  " + p.getUsername() + " -> ", NamedTextColor.WHITE)
                        .append(Component.text(state.name(), color)));
            }
        }
    }

    private void handleDebug(CommandSource source, String[] args) {
        if (plugin.getConfig() == null) {
            source.sendMessage(Component.text("Plugin is not initialized.", NamedTextColor.RED));
            return;
        }
        boolean enable;
        if (args.length >= 2) {
            enable = args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true");
        } else {
            // Toggle
            enable = !plugin.getConfig().isDebug();
        }
        plugin.getConfig().setDebug(enable);
        source.sendMessage(Component.text("Debug mode " + (enable ? "enabled" : "disabled") + ".",
                enable ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        plugin.getLogger().info("Debug mode {} by {}", enable ? "enabled" : "disabled",
                source instanceof Player p ? p.getUsername() : "console");
    }

    private void handleReload(CommandSource source) {
        if (plugin.getConfig() == null) {
            source.sendMessage(Component.text("Plugin is not initialized.", NamedTextColor.RED));
            return;
        }
        try {
            plugin.getConfig().reload();
            source.sendMessage(Component.text("Configuration reloaded successfully.", NamedTextColor.GREEN));
            plugin.getLogger().info("Config reloaded by {}",
                    source instanceof Player p ? p.getUsername() : "console");
        } catch (Exception e) {
            source.sendMessage(Component.text("Failed to reload config: " + e.getMessage(), NamedTextColor.RED));
            plugin.getLogger().error("Config reload failed", e);
        }
    }

    private void sendHelp(CommandSource source) {
        source.sendMessage(Component.text("BetterLogin commands:", NamedTextColor.GOLD));
        source.sendMessage(Component.text("  /betterlogin status          ", NamedTextColor.YELLOW)
                .append(Component.text("- show plugin status and player auth states", NamedTextColor.GRAY)));
        source.sendMessage(Component.text("  /betterlogin debug [on|off]  ", NamedTextColor.YELLOW)
                .append(Component.text("- toggle verbose debug logging", NamedTextColor.GRAY)));
        source.sendMessage(Component.text("  /betterlogin reload          ", NamedTextColor.YELLOW)
                .append(Component.text("- reload config.yml", NamedTextColor.GRAY)));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            return List.of("status", "debug", "reload");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            return List.of("on", "off");
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("betterlogin.admin");
    }
}
