package com.betterlogin.paper;

import com.betterlogin.paper.command.BetterLoginTestCommand;
import com.betterlogin.paper.config.PaperConfig;
import com.betterlogin.paper.dialog.DialogHandler;
import com.betterlogin.paper.dialog.VanillaDialogHandler;
import com.betterlogin.paper.listener.AuthPlayerListener;
import com.betterlogin.paper.listener.BridgeMessageListener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Paper bridge plugin for BetterLogin.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Receive plugin-channel messages from the Velocity proxy</li>
 *   <li>Show the native Minecraft dialog (1.21.6+ clients) or a chat-command prompt
 *       (older clients) for password entry</li>
 *   <li>Freeze unauthenticated players (dialog-path only) until auth completes</li>
 *   <li>Execute post-auth commands on the Paper server</li>
 * </ul>
 *
 * <p><strong>Note:</strong> PacketEvents is intentionally NOT initialised here.
 * The standalone {@code packetevents} plugin (used by GrimAC and others) handles its
 * own lifecycle.  Bundling a second copy caused double-injection into the Netty
 * pipeline and kicked every player with "PacketEvents 2.0 failed to inject".</p>
 */
public class BetterLoginBridge extends JavaPlugin {

    public static final String CHANNEL = "betterlogin:bridge";

    /** UUIDs of players currently inside the dialog authentication flow. */
    private final Set<UUID> pendingAuth = Collections.synchronizedSet(new HashSet<>());

    private PaperConfig paperConfig;
    private DialogHandler dialogHandler;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        paperConfig = new PaperConfig(this);
        dialogHandler = new VanillaDialogHandler(this, pendingAuth, paperConfig);
        BridgeMessageListener bridgeListener =
                new BridgeMessageListener(this, dialogHandler, pendingAuth);

        // Register plugin-message channel (incoming from Velocity via player connection)
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, bridgeListener);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);

        // Auth player listener (freeze movement, block commands, etc.)
        getServer().getPluginManager().registerEvents(
                new AuthPlayerListener(this, dialogHandler, pendingAuth), this);

        // Admin / debug command
        BetterLoginTestCommand testCmd = new BetterLoginTestCommand(this);
        getCommand("betterlogintest").setExecutor(testCmd);
        getCommand("betterlogintest").setTabCompleter(testCmd);

        getLogger().info("BetterLogin bridge enabled.");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this, CHANNEL);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
    }

    public Set<UUID> getPendingAuth() { return pendingAuth; }
    public DialogHandler getDialogHandler() { return dialogHandler; }
    public PaperConfig getPaperConfig() { return paperConfig; }
}

