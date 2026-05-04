package com.betterlogin.paper.listener;

import com.betterlogin.paper.BetterLoginBridge;
import com.betterlogin.paper.config.PaperConfig;
import com.betterlogin.paper.dialog.DialogHandler;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Listens for plugin-channel messages from an optional Velocity proxy on channel
 * {@value BetterLoginBridge#CHANNEL}.
 *
 * <h2>Handled message types</h2>
 * <pre>
 * AUTH_REQUIRED\0{uuid}\0{isNewPlayer}  – optional Velocity request; skipped if dialog
 *                                         was already triggered locally on join
 * AUTH_SUCCESS\0{uuid}                  – premium / session player auto-authenticated by Velocity
 * RUN_COMMANDS\0{uuid}\0{username}\0... – execute console commands after auth
 * </pre>
 */
public class BridgeMessageListener implements PluginMessageListener {

    private static final String SEP = "\0";
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private final BetterLoginBridge plugin;
    private final DialogHandler dialogHandler;
    private final Set<UUID> pendingAuth;

    public BridgeMessageListener(BetterLoginBridge plugin, DialogHandler dialogHandler,
                                  Set<UUID> pendingAuth) {
        this.plugin        = plugin;
        this.dialogHandler = dialogHandler;
        this.pendingAuth   = pendingAuth;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] rawData) {
        if (!BetterLoginBridge.CHANNEL.equals(channel)) return;

        String raw = new String(rawData, StandardCharsets.UTF_8);
        String[] parts = raw.split(SEP, -1);
        if (parts.length < 2) return;

        String type = parts[0];
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[DEBUG] Plugin message received: type=" + type
                    + " player=" + player.getName() + " parts=" + parts.length);
        }
        switch (type) {
            case "AUTH_REQUIRED" -> handleAuthRequired(parts, player);
            case "AUTH_SUCCESS"  -> handleAuthSuccess(player);
            case "RUN_COMMANDS"  -> handleRunCommands(parts);
            default -> plugin.getLogger().warning("Unknown bridge message type: " + type);
        }
    }

    // ------------------------------------------------------------------

    /**
     * Velocity requesting auth for this player.  Skipped if the dialog was already
     * triggered locally (player is already in pendingAuth).
     */
    private void handleAuthRequired(String[] parts, Player player) {
        if (parts.length < 3) return;
        boolean isNewPlayer = Boolean.parseBoolean(parts[2]);
        UUID uuid = player.getUniqueId();

        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[DEBUG] AUTH_REQUIRED: player=" + player.getName()
                    + " isNewPlayer=" + isNewPlayer);
        }

        // If the dialog was already shown locally on join, skip the duplicate request.
        if (pendingAuth.contains(uuid)) {
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("[DEBUG] AUTH_REQUIRED skipped – player already pending: " + player.getName());
            }
            plugin.getPendingDialogRequests().remove(uuid);
            return;
        }

        // Store the request; AuthPlayerListener.onJoin() will pick it up if PlayerJoinEvent
        // has not fired yet (very rare race condition).
        plugin.getPendingDialogRequests().put(uuid, isNewPlayer);

        // Show the dialog on the next main-thread tick if the player is already in-game.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!plugin.getPendingDialogRequests().containsKey(uuid)) return;
            if (!player.isOnline()) return;
            plugin.getPendingDialogRequests().remove(uuid);
            plugin.showAuthDialog(player, isNewPlayer);
        });
    }

    /** Velocity signals that a premium / session player authenticated automatically. */
    private void handleAuthSuccess(Player player) {
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[DEBUG] AUTH_SUCCESS: player=" + player.getName());
        }
        pendingAuth.remove(player.getUniqueId());

        PaperConfig cfg = plugin.getPaperConfig();
        String welcomeMsg = cfg.getWelcomeMessage().replace("{player}", player.getName());
        player.sendMessage(LEGACY.deserialize(welcomeMsg));
    }

    private void handleRunCommands(String[] parts) {
        if (parts.length < 4) return;
        List<String> commands = Arrays.asList(parts).subList(3, parts.length);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (String cmd : commands) {
                plugin.getServer().dispatchCommand(
                        plugin.getServer().getConsoleSender(), cmd);
            }
        });
    }
}
