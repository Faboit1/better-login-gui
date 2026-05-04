package com.betterlogin.paper;

import com.betterlogin.paper.command.BetterLoginTestCommand;
import com.betterlogin.paper.config.PaperConfig;
import com.betterlogin.paper.dialog.DialogHandler;
import com.betterlogin.paper.dialog.VanillaDialogHandler;
import com.betterlogin.paper.listener.AuthMeListener;
import com.betterlogin.paper.listener.AuthPlayerListener;
import com.betterlogin.paper.listener.BridgeMessageListener;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paper bridge plugin for BetterLogin.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>On player join, check AuthMe registration status and show the appropriate dialog.</li>
 *   <li>Show the native Minecraft dialog (1.21.6+ clients) or a chat-command prompt
 *       (older clients) for password entry.</li>
 *   <li>Freeze unauthenticated players until auth completes.</li>
 *   <li>Optionally notify a Velocity proxy via plugin-channel messages for server routing.</li>
 * </ul>
 */
public class BetterLoginBridge extends JavaPlugin {

    public static final String CHANNEL = "betterlogin:bridge";
    /** Null-byte separator used in plugin-message payloads. */
    public static final String SEP = "\0";
    /** Outbound message type sent from Paper to Velocity when the player is fully in-game. */
    public static final String MSG_PLAYER_READY = "PLAYER_READY";

    /** UUIDs of players currently inside the authentication flow. */
    private final Set<UUID> pendingAuth = Collections.synchronizedSet(new HashSet<>());

    /**
     * AUTH_REQUIRED messages received before the player's {@code PlayerJoinEvent} has fired.
     * Maps UUID → isNewPlayer.  {@link AuthPlayerListener} picks this up on join.
     */
    private final Map<UUID, Boolean> pendingDialogRequests = new ConcurrentHashMap<>();

    private PaperConfig paperConfig;
    private DialogHandler dialogHandler;

    /**
     * Returns {@code true} if AuthMe is installed and considers this player registered.
     * Uses reflection so there is no compile-time hard dependency on AuthMe.
     */
    public boolean isAuthMeRegistered(Player player) {
        if (!getServer().getPluginManager().isPluginEnabled("AuthMe")) return false;
        try {
            Class<?> apiClass = Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            if (api == null) return false;
            return (boolean) apiClass.getMethod("isRegistered", String.class).invoke(api, player.getName());
        } catch (Exception e) {
            if (getConfig().getBoolean("debug", false)) {
                getLogger().warning("[DEBUG] AuthMe API call failed for " + player.getName() + ": " + e.getMessage());
            }
            return false;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        paperConfig   = new PaperConfig(this);
        dialogHandler = new VanillaDialogHandler(this, pendingAuth, paperConfig);

        BridgeMessageListener bridgeListener =
                new BridgeMessageListener(this, dialogHandler, pendingAuth);

        // Plugin-message channel (incoming from Velocity via player connection)
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, bridgeListener);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);

        // Auth player listener (freeze movement, block commands, handle join timing)
        getServer().getPluginManager().registerEvents(
                new AuthPlayerListener(this, dialogHandler, pendingAuth), this);

        // Admin / debug command
        BetterLoginTestCommand testCmd = new BetterLoginTestCommand(this);
        getCommand("betterlogintest").setExecutor(testCmd);
        getCommand("betterlogintest").setTabCompleter(testCmd);

        // Register AuthMe event listener to detect when authentication succeeds.
        // AuthMe is a soft-depend; if it is not installed the listener is simply not registered.
        if (getServer().getPluginManager().isPluginEnabled("AuthMe")) {
            if (AuthMeListener.register(this)) {
                getLogger().info("AuthMe detected – BetterLogin will delegate authentication to AuthMe.");
            } else {
                getLogger().warning("AuthMe is installed but its event classes could not be found. "
                        + "Authentication events will not be processed.");
            }
        } else {
            getLogger().warning("AuthMe is not installed! BetterLogin requires AuthMe to handle "
                    + "credential verification. Players will not be able to authenticate.");
        }

        getLogger().info("BetterLogin bridge enabled.");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this, CHANNEL);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Auth dialog helper
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Opens the appropriate login or registration dialog for the player.
     * Call this only from the main thread (or via a scheduled task).
     */
    public void showAuthDialog(Player player, boolean isNewPlayer) {
        if (isNewPlayer) {
            dialogHandler.showRegisterDialog(player);
        } else {
            dialogHandler.showLoginDialog(player);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Accessors
    // ─────────────────────────────────────────────────────────────────────────

    public Set<UUID> getPendingAuth()                    { return pendingAuth; }
    public Map<UUID, Boolean> getPendingDialogRequests() { return pendingDialogRequests; }
    public DialogHandler getDialogHandler()              { return dialogHandler; }
    public PaperConfig getPaperConfig()                  { return paperConfig; }
}
