package com.betterlogin.paper.listener;

import com.betterlogin.paper.BetterLoginBridge;
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
 * AUTH_REQUIRED\0{uuid}\0{isNewPlayer}  – show dialog to player
 * AUTH_SUCCESS\0{uuid}                  – mark player as authenticated (premium / session)
 * AUTH_RESULT\0{uuid}\0{success}\0{msg} – outcome of a submitted password
 * RUN_COMMANDS\0{uuid}\0{username}\0... – execute console commands
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
        switch (type) {
            case "AUTH_REQUIRED" -> handleAuthRequired(parts, player);
            case "AUTH_SUCCESS"  -> handleAuthSuccess(parts, player);
            case "AUTH_RESULT"   -> handleAuthResult(parts, player);
            case "RUN_COMMANDS"  -> handleRunCommands(parts);
            default -> plugin.getLogger().warning("Unknown bridge message type: " + type);
        }
    }

    // ------------------------------------------------------------------

    private void handleAuthRequired(String[] parts, Player player) {
        // AUTH_REQUIRED\0{uuid}\0{isNewPlayer}
        if (parts.length < 3) return;
        boolean isNewPlayer = Boolean.parseBoolean(parts[2]);

        // pendingAuth is managed by VanillaDialogHandler:
        //  - 1.21.6+ clients: added when the dialog is shown, removed on response or quit.
        //  - Older clients:   NOT added – AuthMe + AuthMeVelocity handle those players.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (isNewPlayer) {
                dialogHandler.showRegisterDialog(player);
            } else {
                dialogHandler.showLoginDialog(player);
            }
        });
    }

    private void handleAuthSuccess(String[] parts, Player player) {
        // AUTH_SUCCESS\0{uuid} – premium / session auto-login
        pendingAuth.remove(player.getUniqueId());
        String welcomeMsg = plugin.getConfig().getString("messages.welcome",
            "&aWelcome, {player}!")
            .replace("{player}", player.getName());
        player.sendMessage(LEGACY.deserialize(welcomeMsg));
    }

    private void handleAuthResult(String[] parts, Player player) {
        // AUTH_RESULT\0{uuid}\0{success}\0{message}
        if (parts.length < 4) return;
        boolean success = Boolean.parseBoolean(parts[2]);
        String message  = parts[3];

        player.sendMessage(LEGACY.deserialize(message));

        if (success) {
            pendingAuth.remove(player.getUniqueId());
        } else {
            // Re-show dialog on main thread so the player can try again
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && pendingAuth.contains(player.getUniqueId())) {
                    // Determine if this player is still registering by checking if they have
                    // a record; if the server says AUTH_RESULT but they're still pending it
                    // means login failed – show login dialog again
                    dialogHandler.showLoginDialog(player);
                }
            }, 20L); // 1 second delay
        }
    }

    private void handleRunCommands(String[] parts) {
        // RUN_COMMANDS\0{uuid}\0{username}\0{cmd1}\0{cmd2}...
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
