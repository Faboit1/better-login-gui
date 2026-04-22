package com.betterlogin.velocity.storage;

import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.Optional;
import java.util.UUID;

/** SQLite-backed storage implementation. Thread-safe via synchronized JDBC calls. */
public class SQLiteStorage implements AuthStorage {

    private final Path dataDirectory;
    private final Logger logger;
    private Connection connection;

    private static final String CREATE_TABLE = """
        CREATE TABLE IF NOT EXISTS players (
            uuid         TEXT PRIMARY KEY,
            username     TEXT NOT NULL,
            password_hash TEXT,
            premium      INTEGER NOT NULL DEFAULT 0,
            last_login   INTEGER NOT NULL DEFAULT 0,
            session_token TEXT
        )
        """;

    private static final String SELECT_PLAYER =
        "SELECT uuid, username, password_hash, premium, last_login, session_token FROM players WHERE uuid = ?";

    private static final String UPSERT_PLAYER = """
        INSERT INTO players (uuid, username, password_hash, premium, last_login, session_token)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(uuid) DO UPDATE SET
            username      = excluded.username,
            password_hash = excluded.password_hash,
            premium       = excluded.premium,
            last_login    = excluded.last_login,
            session_token = excluded.session_token
        """;

    public SQLiteStorage(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }

    @Override
    public void init() {
        try {
            Files.createDirectories(dataDirectory);
            String url = "jdbc:sqlite:" + dataDirectory.resolve("auth.db");
            connection = DriverManager.getConnection(url);
            // Enable WAL for better concurrent read performance
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute(CREATE_TABLE);
            }
            logger.info("SQLiteStorage ready at {}", dataDirectory.resolve("auth.db"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize SQLite storage", e);
        }
    }

    @Override
    public synchronized Optional<PlayerRecord> getPlayer(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(SELECT_PLAYER)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new PlayerRecord(
                    UUID.fromString(rs.getString("uuid")),
                    rs.getString("username"),
                    rs.getString("password_hash"),
                    rs.getInt("premium") == 1,
                    rs.getLong("last_login"),
                    rs.getString("session_token")
                ));
            }
        } catch (SQLException e) {
            logger.error("Failed to query player {}", uuid, e);
            return Optional.empty();
        }
    }

    @Override
    public synchronized void savePlayer(PlayerRecord record) {
        try (PreparedStatement ps = connection.prepareStatement(UPSERT_PLAYER)) {
            ps.setString(1, record.uuid().toString());
            ps.setString(2, record.username());
            ps.setString(3, record.passwordHash());
            ps.setInt(4, record.premium() ? 1 : 0);
            ps.setLong(5, record.lastLogin());
            ps.setString(6, record.sessionToken());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to save player {}", record.uuid(), e);
        }
    }

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            logger.error("Error closing SQLite connection", e);
        }
    }
}
