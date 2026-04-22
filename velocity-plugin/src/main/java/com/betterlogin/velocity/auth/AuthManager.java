package com.betterlogin.velocity.auth;

import com.betterlogin.velocity.config.PluginConfig;
import com.betterlogin.velocity.storage.AuthStorage;
import com.betterlogin.velocity.storage.PlayerRecord;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-player authentication state in memory and coordinates with storage.
 * All methods are thread-safe.
 */
public class AuthManager {

    private final AuthStorage storage;
    private final PluginConfig config;
    private final Logger logger;

    /** In-memory auth state map. Cleared on disconnect. */
    private final Map<UUID, AuthState> states = new ConcurrentHashMap<>();

    /** Tracks consecutive failed login/register attempts per player. */
    private final Map<UUID, Integer> failedAttempts = new ConcurrentHashMap<>();

    public AuthManager(AuthStorage storage, PluginConfig config, Logger logger) {
        this.storage = storage;
        this.config = config;
        this.logger = logger;
    }

    /** Called when a player connects to the proxy. Returns the initial state. */
    public AuthState onPlayerConnect(UUID uuid, String username, boolean onlineMode) {
        if (onlineMode) {
            states.put(uuid, AuthState.PREMIUM);
            // Ensure the player exists in storage as a premium account
            if (storage.getPlayer(uuid).isEmpty()) {
                storage.savePlayer(new PlayerRecord(uuid, username, null, true,
                    Instant.now().getEpochSecond(), null));
            }
            return AuthState.PREMIUM;
        }

        Optional<PlayerRecord> record = storage.getPlayer(uuid);
        if (record.isPresent()) {
            PlayerRecord rec = record.get();
            // Check if an unexpired session token exists
            if (config.isSessionEnabled() && rec.sessionToken() != null) {
                long sessionMaxAge = config.getSessionMaxAgeSeconds();
                long elapsed = Instant.now().getEpochSecond() - rec.lastLogin();
                if (elapsed < sessionMaxAge) {
                    states.put(uuid, AuthState.SESSION_VALID);
                    return AuthState.SESSION_VALID;
                }
            }
            states.put(uuid, AuthState.PENDING_DIALOG);
            return AuthState.PENDING_DIALOG;
        } else {
            // First join – registration flow
            states.put(uuid, AuthState.PENDING_DIALOG);
            return AuthState.PENDING_DIALOG;
        }
    }

    public AuthState getState(UUID uuid) {
        return states.getOrDefault(uuid, AuthState.UNKNOWN);
    }

    public boolean isAuthenticated(UUID uuid) {
        AuthState s = getState(uuid);
        return s == AuthState.AUTHENTICATED || s == AuthState.PREMIUM || s == AuthState.SESSION_VALID;
    }

    public void setState(UUID uuid, AuthState state) {
        states.put(uuid, state);
    }

    /**
     * Processes a registration attempt. Returns true and saves the player on success.
     */
    public boolean tryRegister(UUID uuid, String username, String plainPassword) {
        if (storage.getPlayer(uuid).isPresent()) {
            return false; // already registered
        }
        String hash = PasswordHasher.hash(plainPassword);
        PlayerRecord record = new PlayerRecord(uuid, username, hash, false,
            Instant.now().getEpochSecond(), generateSessionToken());
        storage.savePlayer(record);
        states.put(uuid, AuthState.AUTHENTICATED);
        failedAttempts.remove(uuid);
        return true;
    }

    /**
     * Processes a login attempt. Returns true on success.
     */
    public boolean tryLogin(UUID uuid, String plainPassword) {
        Optional<PlayerRecord> opt = storage.getPlayer(uuid);
        if (opt.isEmpty() || opt.get().passwordHash() == null) return false;

        PlayerRecord rec = opt.get();
        if (!PasswordHasher.verify(plainPassword, rec.passwordHash())) {
            int attempts = failedAttempts.merge(uuid, 1, Integer::sum);
            if (attempts >= config.getMaxLoginAttempts()) {
                states.put(uuid, AuthState.KICKED);
            }
            return false;
        }

        // Update last login and session token
        PlayerRecord updated = new PlayerRecord(rec.uuid(), rec.username(), rec.passwordHash(),
            rec.premium(), Instant.now().getEpochSecond(), generateSessionToken());
        storage.savePlayer(updated);
        states.put(uuid, AuthState.AUTHENTICATED);
        failedAttempts.remove(uuid);
        return true;
    }

    /** Returns true if this player has never registered before. */
    public boolean isNewPlayer(UUID uuid) {
        return storage.getPlayer(uuid).isEmpty();
    }

    public int getFailedAttempts(UUID uuid) {
        return failedAttempts.getOrDefault(uuid, 0);
    }

    /** Clean up all in-memory state for a disconnecting player. */
    public void onPlayerDisconnect(UUID uuid) {
        states.remove(uuid);
        failedAttempts.remove(uuid);
    }

    private String generateSessionToken() {
        return UUID.randomUUID().toString();
    }
}
