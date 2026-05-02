package com.betterlogin.velocity;

import com.betterlogin.velocity.auth.AuthManager;
import com.betterlogin.velocity.bridge.BridgeMessenger;
import com.betterlogin.velocity.command.BetterLoginCommand;
import com.betterlogin.velocity.config.PluginConfig;
import com.betterlogin.velocity.listener.LoginListener;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
    id = "better-login",
    name = "BetterLogin",
    version = "1.0.0",
    description = "Seamless vanilla-dialog authentication for hybrid Minecraft networks",
    authors = {"BetterLogin"}
)
public class BetterLoginPlugin {

    public static final MinecraftChannelIdentifier BRIDGE_CHANNEL =
        MinecraftChannelIdentifier.from("betterlogin:bridge");

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private PluginConfig config;
    private AuthManager authManager;
    private BridgeMessenger bridgeMessenger;

    @Inject
    public BetterLoginPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            this.config = new PluginConfig(dataDirectory, logger);
            this.authManager = new AuthManager(config, logger);
            this.bridgeMessenger = new BridgeMessenger(proxy, authManager, config, logger);

            proxy.getChannelRegistrar().register(BRIDGE_CHANNEL);
            proxy.getEventManager().register(this, new LoginListener(proxy, this, authManager, bridgeMessenger, config, logger));
            proxy.getEventManager().register(this, bridgeMessenger);

            // Register /betterlogin admin command
            CommandMeta meta = proxy.getCommandManager()
                    .metaBuilder("betterlogin")
                    .aliases("bl")
                    .plugin(this)
                    .build();
            proxy.getCommandManager().register(meta, new BetterLoginCommand(this));

            logger.info("BetterLogin initialized successfully.");
        } catch (Exception e) {
            logger.error("Failed to initialize BetterLogin. Plugin will be inactive.", e);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("BetterLogin shut down.");
    }

    public ProxyServer getProxy() { return proxy; }
    public Logger getLogger() { return logger; }
    public PluginConfig getConfig() { return config; }
    public AuthManager getAuthManager() { return authManager; }
    public BridgeMessenger getBridgeMessenger() { return bridgeMessenger; }
}
