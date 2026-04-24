package com.betterlogin.velocity.bridge;

import com.betterlogin.velocity.auth.AuthManager;
import com.betterlogin.velocity.auth.AuthState;
import com.betterlogin.velocity.config.PluginConfig;
import com.betterlogin.velocity.listener.LoginListener;
import com.betterlogin.velocity.storage.AuthStorage;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.betterlogin.velocity.BetterLoginPlugin.BRIDGE_CHANNEL;

/**
 * Handles plugin-messaging communication between the Velocity proxy and the Paper bridge plugin.
 *
 * <h2>Protocol (UTF-8 plain-text, fields separated by {@code \0})</h2>
 * <pre>
 * Velocity  → Paper : AUTH_REQUIRED\0{uuid}\0{isNewPlayer}
 * Velocity  → Paper : AUTH_SUCCESS\0{uuid}
 * Velocity  → Paper : RUN_COMMANDS\0{uuid}\0{username}\0{cmd1}\0{cmd2}...
 * Paper     → Velocity : AUTH_ATTEMPT\0{uuid}\0{username}\0{isRegister}\0{password}
 * </pre>
 */
public class BridgeMessenger {

    private final ProxyServer proxy;
    private final AuthManager authManager;
    private final AuthStorage storage;
    private final PluginConfig config;
    private final Logger logger;

    private static final String SEP = "\0";

    public BridgeMessenger(ProxyServer proxy, AuthManager authManager, AuthStorage storage,
                           PluginConfig config, Logger logger) {
        this.proxy       = proxy;
        this.authManager = authManager;
        this.storage     = storage;
        this.config      = config;
        this.logger      = logger;
    }

    // ------------------------------------------------------------------
    // Outbound messages (Velocity → Paper)
    // ------------------------------------------------------------------

    /** Tell the Paper bridge to start the auth dialog for this player. */
    public void sendAuthRequired(Player player, boolean isNewPlayer) {
        String payload = String.join(SEP,
            "AUTH_REQUIRED",
            player.getUniqueId().toString(),
            String.valueOf(isNewPlayer)
        );
        if (config.isDebug()) {
            logger.info("[DEBUG] → Paper AUTH_REQUIRED: player={} isNewPlayer={}", player.getUsername(), isNewPlayer);
        }
        send(player, payload);
        authManager.setState(player.getUniqueId(), AuthState.PENDING_DIALOG);
    }

    /** Notify Paper that this player has been auto-authenticated (premium or valid session). */
    public void sendAuthSuccess(Player player) {
        String payload = String.join(SEP,
            "AUTH_SUCCESS",
            player.getUniqueId().toString()
        );
        if (config.isDebug()) {
            logger.info("[DEBUG] → Paper AUTH_SUCCESS: player={}", player.getUsername());
        }
        send(player, payload);
        runPostAuthCommands(player, false);
    }

    /** Tell Paper to execute a list of console commands after authentication. */
    private void sendRunCommands(Player player, List<String> commands) {
        if (commands.isEmpty()) return;
        String[] parts = new String[commands.size() + 3];
        parts[0] = "RUN_COMMANDS";
        parts[1] = player.getUniqueId().toString();
        parts[2] = player.getUsername();
        for (int i = 0; i < commands.size(); i++) {
            parts[i + 3] = commands.get(i)
                .replace("{player}", player.getUsername())
                .replace("{uuid}", player.getUniqueId().toString());
        }
        send(player, String.join(SEP, parts));
    }

    // ------------------------------------------------------------------
    // Inbound messages (Paper → Velocity)
    // ------------------------------------------------------------------

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(BRIDGE_CHANNEL)) return;
        if (!(event.getSource() instanceof ServerConnection)) return;

        event.setResult(PluginMessageEvent.ForwardResult.handled());

        String raw = new String(event.getData(), StandardCharsets.UTF_8);
        String[] parts = raw.split(SEP, -1);
        if (parts.length < 2) return;

        String type = parts[0];
        if (config.isDebug()) {
            logger.info("[DEBUG] ← Paper plugin message: type={} parts={}", type, parts.length);
        }
        if ("AUTH_ATTEMPT".equals(type)) {
            handleAuthAttempt(parts);
        }
    }

    private void handleAuthAttempt(String[] parts) {
        // AUTH_ATTEMPT\0{uuid}\0{username}\0{isRegister}\0{password}
        if (parts.length < 5) {
            logger.warn("Malformed AUTH_ATTEMPT message – ignoring");
            return;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid UUID in AUTH_ATTEMPT: {}", parts[1]);
            return;
        }
        String username  = parts[2];
        boolean register = Boolean.parseBoolean(parts[3]);
        String password  = parts[4];

        Optional<Player> optPlayer = proxy.getPlayer(uuid);
        if (optPlayer.isEmpty()) return;
        Player player = optPlayer.get();

        if (password.length() < config.getMinPasswordLength()) {
            sendAuthResult(player, false, config.getMsgPasswordTooShort());
            return;
        }

        boolean success;
        boolean wasRegister = register;
        if (register) {
            success = authManager.tryRegister(uuid, username, password);
            if (!success) {
                sendAuthResult(player, false, config.getMsgAlreadyRegistered());
                return;
            }
        } else {
            success = authManager.tryLogin(uuid, password);
        }

        if (success) {
            String msg = wasRegister ? config.getMsgRegisterSuccess() : config.getMsgLoginSuccess();
            if (config.isDebug()) {
                logger.info("[DEBUG] AUTH_ATTEMPT success: player={} register={}", username, wasRegister);
            }
            sendAuthResult(player, true, msg);
            runPostAuthCommands(player, wasRegister);
        } else {
            if (authManager.getState(uuid) == AuthState.KICKED) {
                if (config.isDebug()) {
                    logger.info("[DEBUG] AUTH_ATTEMPT: player={} kicked after too many failures", username);
                }
                LoginListener.kick(player, config.getMsgKicked());
                return;
            }
            if (config.isDebug()) {
                logger.info("[DEBUG] AUTH_ATTEMPT failure: player={} attempts={}", username, authManager.getFailedAttempts(uuid));
            }
            sendAuthResult(player, false, config.getMsgLoginFailed());
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void sendAuthResult(Player player, boolean success, String message) {
        String payload = String.join(SEP,
            "AUTH_RESULT",
            player.getUniqueId().toString(),
            String.valueOf(success),
            message
        );
        send(player, payload);
    }

    private void runPostAuthCommands(Player player, boolean wasRegister) {
        List<String> cmds = wasRegister ? config.getOnRegisterCommands() : config.getOnLoginCommands();
        sendRunCommands(player, cmds);
    }

    private void send(Player player, String payload) {
        player.getCurrentServer().ifPresentOrElse(
            server -> {
                if (config.isDebug()) {
                    logger.info("[DEBUG] Sending plugin message to {} on server {}: {}",
                            player.getUsername(), server.getServerInfo().getName(),
                            payload.split(SEP, 2)[0]);
                }
                server.sendPluginMessage(BRIDGE_CHANNEL, payload.getBytes(StandardCharsets.UTF_8));
            },
            () -> logger.warn("Cannot send plugin message to {} – player has no current server", player.getUsername())
        );
    }
}
