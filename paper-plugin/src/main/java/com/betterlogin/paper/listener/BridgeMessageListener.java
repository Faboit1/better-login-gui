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
 * Listens for plugin-channel messages from the Velocity proxy on channel {@value BetterLoginBridge#CHANNEL}.
 *
 * <h2>Handled message types</h2>
 * <pre>
 * AUTH_REQUIRED\0{uuid}\0{isNewPlayer}  – queue dialog for player (shown on or after PlayerJoinEvent)
 * AUTH_SUCCESS\0{uuid}                  – mark player as authenticated (premium / session)
 * AUTH_RESULT\0{uuid}\0{success}\0{msg} – outcome of a submitted password
 * RUN_COMMANDS\0{uuid}\0{username}\0... – execute console commands
 * </pre>
 *
 * <h2>Timing note</h2>
 * <p>Velocity fires {@code ServerConnectedEvent} and immediately sends {@code AUTH_REQUIRED}.
 * At that moment Paper may not have fully spawned the player (i.e. {@code PlayerJoinEvent}
 * has not yet fired), which means {@code player.showDialog()} would silently fail.  To avoid
 * this, {@code handleAuthRequired} stores the request in
 * {@link BetterLoginBridge#getPendingDialogRequests()}.  If the player is already online the
 * dialog is shown immediately on the next tick; otherwise
 * {@link AuthPlayerListener#onJoin} picks it up when the player spawns.</p>
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
        this.plugin = plugin;
        this.dialogHandler = dialogHandler;
        this.pendingAuth = pendingAuth;
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
            case "AUTH_SUCCESS"  -> handleAuthSuccess(parts, player);
            case "AUTH_RESULT"   -> handleAuthResult(parts, player);
            case "RUN_COMMANDS"  -> handleRunCommands(parts);
            default -> plugin.getLogger().warning("Unknown bridge message type: " + type);
        }
    }

    // ------------------------------------------------------------------

    /**
     * Queues the dialog request and shows it once the player is fully spawned.
     *
     * <p>The request is stored in {@code pendingDialogRequests}.  A runTask is
     * scheduled for the next tick.  If the player is already online by then, the
     * dialog is shown immediately.  Otherwise, {@link AuthPlayerListener#onJoin}
     * removes the entry and shows the dialog after {@code PlayerJoinEvent}.</p>
     */
    private void handleAuthRequired(String[] parts, Player player) {
        if (parts.length < 3) return;
        boolean isNewPlayer = Boolean.parseBoolean(parts[2]);
        UUID uuid = player.getUniqueId();

        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[DEBUG] AUTH_REQUIRED: player=" + player.getName()
                    + " isNewPlayer=" + isNewPlayer);
        }

        // Store the request; AuthPlayerListener.onJoin() will pick it up if the player
        // hasn't fully spawned yet.
        plugin.getPendingDialogRequests().put(uuid, isNewPlayer);

        // Also try immediately on the next main-thread tick in case PlayerJoinEvent has
        // already fired (e.g. player switched servers on an already-loaded backend).
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            // Check if AuthPlayerListener.onJoin() already handled this (map entry gone)
            if (!plugin.getPendingDialogRequests().containsKey(uuid)) return;
            if (!player.isOnline()) return;
            plugin.getPendingDialogRequests().remove(uuid);
            showDialogWithEffects(player, isNewPlayer);
        });
    }

    private void handleAuthSuccess(String[] parts, Player player) {
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[DEBUG] AUTH_SUCCESS: player=" + player.getName());
        }
        pendingAuth.remove(player.getUniqueId());
        plugin.removeBossBar(player.getUniqueId());
        player.clearTitle();

        PaperConfig cfg = plugin.getPaperConfig();
        String welcomeMsg = cfg.getWelcomeMessage().replace("{player}", player.getName());
        player.sendMessage(LEGACY.deserialize(welcomeMsg));
    }

    private void handleAuthResult(String[] parts, Player player) {
        if (parts.length < 4) return;
        boolean success = Boolean.parseBoolean(parts[2]);
        String message  = parts[3];

        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[DEBUG] AUTH_RESULT: player=" + player.getName() + " success=" + success);
        }

        player.sendMessage(LEGACY.deserialize(message));

        if (success) {
            pendingAuth.remove(player.getUniqueId());
            plugin.getPendingRegistration().remove(player.getUniqueId());
            plugin.removeBossBar(player.getUniqueId());
            player.clearTitle();
        } else {
            // Re-show dialog on main thread (1 s delay) so the player can try again.
            // For fallback (old client) players, showLoginDialog falls back to the
            // text prompt again, which re-adds them to pendingRegistration.
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && pendingAuth.contains(player.getUniqueId())) {
                    dialogHandler.showLoginDialog(player);
                }
            }, 20L);
        }
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

    // ------------------------------------------------------------------
    // Display helpers
    // ------------------------------------------------------------------

    /**
     * Package-private – delegates to {@link BetterLoginBridge#showAuthEffectsAndDialog}.
     * Kept as a named method so it can be called from unit tests or within this package
     * without exposing it as part of the public API.
     */
    void showDialogWithEffects(Player player, boolean isNewPlayer) {
        plugin.showAuthEffectsAndDialog(player, isNewPlayer);
    }
}
