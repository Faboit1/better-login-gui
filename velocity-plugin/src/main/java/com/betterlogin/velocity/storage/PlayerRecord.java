package com.betterlogin.velocity.storage;

import java.util.UUID;

/**
 * Immutable data record for a single authenticated player.
 *
 * @param uuid         The player's UUID (online-mode UUID for premium, offline-mode UUID for cracked).
 * @param username     Last known username.
 * @param passwordHash BCrypt hash; null for premium players who never set a password.
 * @param premium      Whether the player was verified via Mojang.
 * @param lastLogin    Unix timestamp of the last successful authentication.
 * @param sessionToken Random token used for session resumption; null if sessions are disabled.
 */
public record PlayerRecord(
    UUID uuid,
    String username,
    String passwordHash,
    boolean premium,
    long lastLogin,
    String sessionToken
) {}
