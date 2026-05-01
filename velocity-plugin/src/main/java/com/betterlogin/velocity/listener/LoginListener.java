package com.betterlogin.velocity.listener;

import com.betterlogin.velocity.auth.AuthManager;
import com.betterlogin.velocity.auth.AuthState;
import com.betterlogin.velocity.bridge.BridgeMessenger;
import com.betterlogin.velocity.config.PluginConfig;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Core proxy-level login listener.
 * Intercepts player connections, determines premium vs cracked, and manages routing.
 */
public class LoginListener {

    private final ProxyServer proxy;
    private final Object pluginInstance; // the @Plugin-annotated object, needed for scheduler
    private final AuthManager authManager;
    private final BridgeMessenger bridge;
    private final PluginConfig config;
    private final Logger logger;

    private static final LegacyComponentSerializer LEGACY =
        LegacyComponentSerializer.legacyAmpersand();

    /** Delay before sending plugin messages to let Velocity populate getCurrentServer(). */
    private static final long PLUGIN_MESSAGE_DELAY_MS = 50L;

    public LoginListener(ProxyServer proxy, Object pluginInstance, AuthManager authManager,
                         BridgeMessenger bridge, PluginConfig config, Logger logger) {
        this.proxy          = proxy;
        this.pluginInstance = pluginInstance;
        this.authManager    = authManager;
        this.bridge         = bridge;
        this.config         = config;
        this.logger         = logger;
    }

    /**
     * Runs at login – determines auth state for this player.
     * Velocity sets isOnlineMode() based on whether it verified the player with Mojang.
     */
    @Subscribe(order = PostOrder.LATE)
    public void onLogin(LoginEvent event) {
        if (!event.getResult().isAllowed()) return;

        UUID uuid        = event.getPlayer().getUniqueId();
        String username  = event.getPlayer().getUsername();
        boolean premium  = event.getPlayer().isOnlineMode();

        AuthState state = authManager.onPlayerConnect(uuid, username, premium);
        if (config.isDebug()) {
            logger.info("[DEBUG] LoginEvent: player={} uuid={} premium={} → state={}",
                    username, uuid, premium, state);
        } else {
            logger.debug("Player {} ({}) connected – state={}", username, uuid, state);
        }
    }

    /**
     * Choose the initial server for the player.
     * Unauthenticated offline players go to limbo (if configured); others go to main.
     */
    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        AuthState state = authManager.getState(uuid);

        if (config.isDebug()) {
            logger.info("[DEBUG] ChooseInitialServer: player={} state={}", event.getPlayer().getUsername(), state);
        }

        // Premium and session-valid players skip auth and go directly to main
        if (state == AuthState.PREMIUM || state == AuthState.SESSION_VALID) {
            routeToMain(event);
            return;
        }

        // Offline players needing auth → limbo (if configured) or main server
        String limboName = config.getLimboServer();
        if (limboName != null && !limboName.isBlank()) {
            Optional<RegisteredServer> limbo = proxy.getServer(limboName);
            if (limbo.isPresent()) {
                event.setInitialServer(limbo.get());
                return;
            }
            logger.warn("Limbo server '{}' not found in Velocity config – routing to main", limboName);
        }
        routeToMain(event);
    }

    /** Once connected to a backend, tell the Paper bridge to start the auth dialog (if needed). */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        AuthState state = authManager.getState(uuid);

        if (config.isDebug()) {
            logger.info("[DEBUG] ServerConnected: player={} server={} state={}",
                    event.getPlayer().getUsername(),
                    event.getServer().getServerInfo().getName(),
                    state);
        }

        // Delay by 50 ms so Velocity finishes updating getCurrentServer() before we try to
        // send the plugin message. Sending immediately causes "player has no current server".
        proxy.getScheduler().buildTask(pluginInstance, () -> {
            // Bail out if the player disconnected during the delay
            if (!event.getPlayer().isActive() || authManager.getState(uuid) == AuthState.UNKNOWN) return;

            if (state == AuthState.PREMIUM || state == AuthState.SESSION_VALID || state == AuthState.AUTHENTICATED) {
                // Notify Paper of a successful auto-login so it can run welcome commands
                bridge.sendAuthSuccess(event.getPlayer());
                return;
            }

            // Start auth dialog on the Paper server
            boolean isNewPlayer = authManager.isNewPlayer(uuid);
            bridge.sendAuthRequired(event.getPlayer(), isNewPlayer);
        }).delay(PLUGIN_MESSAGE_DELAY_MS, TimeUnit.MILLISECONDS).schedule();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        authManager.onPlayerDisconnect(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------

    private void routeToMain(PlayerChooseInitialServerEvent event) {
        String mainName = config.getMainServer();
        Optional<RegisteredServer> main = proxy.getServer(mainName);
        if (main.isPresent()) {
            event.setInitialServer(main.get());
        } else {
            logger.error("Main server '{}' not found in Velocity config!", mainName);
        }
    }

    /** Kick a player with a coloured message. */
    public static void kick(com.velocitypowered.api.proxy.Player player, String legacyMessage) {
        player.disconnect(LEGACY.deserialize(legacyMessage));
    }
}
