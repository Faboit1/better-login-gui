package com.betterlogin.paper;

import com.betterlogin.paper.dialog.DialogHandler;
import com.betterlogin.paper.dialog.SignEditorDialogHandler;
import com.betterlogin.paper.listener.AuthPlayerListener;
import com.betterlogin.paper.listener.BridgeMessageListener;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Paper bridge plugin for BetterLogin.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Receive plugin-channel messages from the Velocity proxy</li>
 *   <li>Show the vanilla dialog (sign-editor GUI) for password entry</li>
 *   <li>Freeze unauthenticated players until auth completes</li>
 *   <li>Execute post-auth commands on the Paper server</li>
 * </ul>
 */
public class BetterLoginBridge extends JavaPlugin {

    public static final String CHANNEL = "betterlogin:bridge";

    /** UUIDs of players who are still in the authentication flow. */
    private final Set<UUID> pendingAuth = new HashSet<>();

    private DialogHandler dialogHandler;
    private BridgeMessageListener bridgeListener;

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        PacketEvents.getAPI().init();

        dialogHandler = new SignEditorDialogHandler(this, pendingAuth);
        bridgeListener = new BridgeMessageListener(this, dialogHandler, pendingAuth);

        // Register plugin-message channel (incoming from Velocity via player connection)
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, bridgeListener);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);

        // Auth player listener (freeze movement, block commands, etc.)
        getServer().getPluginManager().registerEvents(
            new AuthPlayerListener(this, dialogHandler, pendingAuth), this);

        getLogger().info("BetterLogin bridge enabled.");
    }

    @Override
    public void onDisable() {
        PacketEvents.getAPI().terminate();
        getServer().getMessenger().unregisterIncomingPluginChannel(this, CHANNEL);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
    }

    public Set<UUID> getPendingAuth() { return pendingAuth; }
    public DialogHandler getDialogHandler() { return dialogHandler; }
}
