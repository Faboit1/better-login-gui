package com.betterlogin.paper;

import com.betterlogin.paper.command.BetterLoginTestCommand;
import com.betterlogin.paper.command.FallbackAuthCommand;
import com.betterlogin.paper.config.PaperConfig;
import com.betterlogin.paper.dialog.DialogHandler;
import com.betterlogin.paper.dialog.VanillaDialogHandler;
import com.betterlogin.paper.listener.AuthPlayerListener;
import com.betterlogin.paper.listener.BridgeMessageListener;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
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
 *   <li>Receive plugin-channel messages from the Velocity proxy</li>
 *   <li>Show the native Minecraft dialog (1.21.6+ clients) or a chat-command prompt
 *       (older clients) for password entry</li>
 *   <li>Freeze unauthenticated players until auth completes</li>
 *   <li>Execute post-auth commands on the Paper server</li>
 * </ul>
 */
public class BetterLoginBridge extends JavaPlugin {

    public static final String CHANNEL = "betterlogin:bridge";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    /** UUIDs of players currently inside the authentication flow. */
    private final Set<UUID> pendingAuth = Collections.synchronizedSet(new HashSet<>());

    /**
     * AUTH_REQUIRED messages received before the player's {@code PlayerJoinEvent} has fired.
     * Maps UUID → isNewPlayer.  {@link AuthPlayerListener} picks this up on join.
     */
    private final Map<UUID, Boolean> pendingDialogRequests = new ConcurrentHashMap<>();

    /**
     * Tracks whether a fallback-mode player (old client, no native dialog) is
     * in the registration flow (true) or login flow (false).
     * Used by {@link com.betterlogin.paper.command.FallbackAuthCommand}.
     */
    private final Map<UUID, Boolean> pendingRegistration = new ConcurrentHashMap<>();

    /** Active Adventure boss bars, keyed by player UUID.  Removed on auth success or quit. */
    private final Map<UUID, BossBar> activeBossBars = new ConcurrentHashMap<>();

    private PaperConfig paperConfig;
    private DialogHandler dialogHandler;

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

        // Fallback /login and /register commands for older clients
        FallbackAuthCommand loginCmd    = new FallbackAuthCommand(this, false);
        FallbackAuthCommand registerCmd = new FallbackAuthCommand(this, true);
        getCommand("login").setExecutor(loginCmd);
        getCommand("register").setExecutor(registerCmd);

        getLogger().info("BetterLogin bridge enabled.");
    }

    @Override
    public void onDisable() {
        // Remove all active boss bars before shutdown
        activeBossBars.forEach((uuid, bar) -> {
            Player p = getServer().getPlayer(uuid);
            if (p != null) p.hideBossBar(bar);
        });
        activeBossBars.clear();

        getServer().getMessenger().unregisterIncomingPluginChannel(this, CHANNEL);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Auth display + dialog helper
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Shows the configured auth-display effects (title, boss bar, action bar) and then
     * opens the appropriate login or registration dialog for the player.
     * Call this only from the main thread (or via a scheduled task).
     */
    public void showAuthEffectsAndDialog(Player player, boolean isNewPlayer) {
        if (paperConfig.isAuthTitleEnabled()) {
            player.showTitle(Title.title(
                    LEGACY.deserialize(isNewPlayer ? paperConfig.getAuthRegisterTitle()    : paperConfig.getAuthLoginTitle()),
                    LEGACY.deserialize(isNewPlayer ? paperConfig.getAuthRegisterSubtitle() : paperConfig.getAuthLoginSubtitle()),
                    Title.Times.times(
                            Duration.ofMillis(paperConfig.getAuthTitleFadeIn()  * 50L),
                            Duration.ofMillis(paperConfig.getAuthTitleStay()    * 50L),
                            Duration.ofMillis(paperConfig.getAuthTitleFadeOut() * 50L)
                    )
            ));
        }
        if (paperConfig.isAuthBossBarEnabled()) {
            showBossBar(player);
        }
        if (paperConfig.isAuthActionBarEnabled()) {
            player.sendActionBar(LEGACY.deserialize(
                    isNewPlayer ? paperConfig.getAuthRegisterActionBar() : paperConfig.getAuthLoginActionBar()));
        }
        if (isNewPlayer) {
            dialogHandler.showRegisterDialog(player);
        } else {
            dialogHandler.showLoginDialog(player);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Boss bar helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Creates and shows an auth boss bar to the player, replacing any existing one. */
    public void showBossBar(Player player) {
        removeBossBar(player.getUniqueId()); // Remove any existing bar first
        BossBar bar = BossBar.bossBar(
                LEGACY.deserialize(paperConfig.getAuthBossBarText()),
                1.0f,
                paperConfig.getAuthBossBarColor(),
                paperConfig.getAuthBossBarStyle()
        );
        player.showBossBar(bar);
        activeBossBars.put(player.getUniqueId(), bar);
    }

    /** Hides and removes the auth boss bar for this player, if one is active. */
    public void removeBossBar(UUID uuid) {
        BossBar bar = activeBossBars.remove(uuid);
        if (bar == null) return;
        Player p = getServer().getPlayer(uuid);
        if (p != null) p.hideBossBar(bar);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Accessors
    // ─────────────────────────────────────────────────────────────────────────

    public Set<UUID> getPendingAuth()              { return pendingAuth; }
    public Map<UUID, Boolean> getPendingDialogRequests() { return pendingDialogRequests; }
    public Map<UUID, Boolean> getPendingRegistration()   { return pendingRegistration; }
    public DialogHandler getDialogHandler()        { return dialogHandler; }
    public PaperConfig getPaperConfig()            { return paperConfig; }
}
