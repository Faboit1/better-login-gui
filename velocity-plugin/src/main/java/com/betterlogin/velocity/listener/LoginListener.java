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
    private static final long PLUGIN_MESSAGE_DELAY_MS = 500L;

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

        // Delay by 500 ms as a fallback for servers where the Paper bridge plugin is not
        // installed (PLAYER_READY would never arrive). If the Paper plugin IS installed it
        // will send PLAYER_READY on PlayerJoinEvent and BridgeMessenger will immediately
        // respond – bridge.wasAuthTriggered() will then be true before this timer fires.
        proxy.getScheduler().buildTask(pluginInstance, () -> {
            // Bail out if the player disconnected during the delay
            if (!event.getPlayer().isActive() || authManager.getState(uuid) == AuthState.UNKNOWN) return;
            // Skip if PLAYER_READY already triggered an immediate send
            if (bridge.wasAuthTriggered(uuid)) return;

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
        UUID uuid = event.getPlayer().getUniqueId();
        authManager.onPlayerDisconnect(uuid);
        bridge.clearAuthTrigger(uuid);
    }

    // ------------------------------------------------------------------

    /**
     * Routes the player to the main backend server.
     *
     * <p>If {@code servers.main} is configured and the server exists, it is used directly.
     * Otherwise the method falls back to Velocity's configured
     * {@code attempt-connection-order} list (from velocity.toml), picking the first
     * server that is registered – so no extra configuration is needed.</p>
     */
    private void routeToMain(PlayerChooseInitialServerEvent event) {
        String mainName = config.getMainServer();

        // Try the explicitly-configured main server first
        if (mainName != null && !mainName.isBlank()) {
            Optional<RegisteredServer> main = proxy.getServer(mainName);
            if (main.isPresent()) {
                event.setInitialServer(main.get());
                return;
            }
            logger.warn("Configured main server '{}' not found in Velocity – falling back to attempt order", mainName);
        }

        // Fall back to Velocity's own attempt-connection-order list
        for (String name : proxy.getConfiguration().getAttemptConnectionOrder()) {
            Optional<RegisteredServer> server = proxy.getServer(name);
            if (server.isPresent()) {
                if (config.isDebug()) {
                    logger.info("[DEBUG] routeToMain: using '{}' from Velocity attempt order", name);
                }
                event.setInitialServer(server.get());
                return;
            }
        }

        logger.error("No suitable main server found in Velocity attempt order – player will be disconnected!");
    }

    /** Kick a player with a coloured message. */
    public static void kick(com.velocitypowered.api.proxy.Player player, String legacyMessage) {
        player.disconnect(LEGACY.deserialize(legacyMessage));
    }
}
