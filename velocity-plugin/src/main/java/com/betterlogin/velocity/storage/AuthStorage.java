package com.betterlogin.velocity.storage;

import java.util.Optional;
import java.util.UUID;

/** Storage abstraction for player authentication records. */
public interface AuthStorage {

    /** Initialise the underlying data source (create tables, connect, etc.). */
    void init();

    /** Retrieve a player record by UUID, or empty if not registered. */
    Optional<PlayerRecord> getPlayer(UUID uuid);

    /**
     * Insert or replace the full player record.
     * Implementations must be thread-safe.
     */
    void savePlayer(PlayerRecord record);

    /** Release resources held by this storage instance. */
    void close();
}
