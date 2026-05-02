package com.betterlogin.velocity.auth;

import com.betterlogin.velocity.config.PluginConfig;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-player authentication state in memory for routing and session decisions.
 *
 * <p>Credential verification is delegated entirely to AuthMe on the Paper backend.
 * This manager only holds the proxy-level state that Velocity needs to route players
 * (e.g. premium auto-auth, session tokens, pending dialog).</p>
 *
 * <p>All methods are thread-safe.</p>
 */
public class AuthManager {

    private final PluginConfig config;
    private final Logger logger;

    /** In-memory auth state map. Cleared on disconnect. */
    private final Map<UUID, AuthState> states = new ConcurrentHashMap<>();

    public AuthManager(PluginConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    /** Called when a player connects to the proxy. Returns the initial state. */
    public AuthState onPlayerConnect(UUID uuid, String username, boolean onlineMode) {
        if (onlineMode) {
            states.put(uuid, AuthState.PREMIUM);
            return AuthState.PREMIUM;
        }
        // Offline players go through the dialog/AuthMe flow.
        states.put(uuid, AuthState.PENDING_DIALOG);
        return AuthState.PENDING_DIALOG;
    }

    public AuthState getState(UUID uuid) {
        return states.getOrDefault(uuid, AuthState.UNKNOWN);
    }

    public boolean isAuthenticated(UUID uuid) {
        AuthState s = getState(uuid);
        return s == AuthState.AUTHENTICATED || s == AuthState.PREMIUM;
    }

    public void setState(UUID uuid, AuthState state) {
        states.put(uuid, state);
    }

    /** Clean up all in-memory state for a disconnecting player. */
    public void onPlayerDisconnect(UUID uuid) {
        states.remove(uuid);
    }
}
