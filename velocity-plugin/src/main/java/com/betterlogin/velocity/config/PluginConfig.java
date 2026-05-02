package com.betterlogin.velocity.config;

import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Loads and exposes plugin configuration from config.yml in the data directory. */
public class PluginConfig {

    private final Path configPath;
    private final Logger logger;

    private boolean debug;

    private String mainServer;
    private String limboServer;

    private List<String> onLoginCommands;
    private List<String> onRegisterCommands;

    public PluginConfig(Path dataDirectory, Logger logger) {
        this.configPath = dataDirectory.resolve("config.yml");
        this.logger = logger;
        reload();
    }

    public void reload() {
        try {
            if (!Files.exists(configPath)) {
                Files.createDirectories(configPath.getParent());
                try (var in = getClass().getResourceAsStream("/config.yml")) {
                    if (in != null) Files.copy(in, configPath);
                }
            }
            YamlConfigurationLoader loader = YamlConfigurationLoader.builder().path(configPath).build();
            CommentedConfigurationNode root = loader.load();

            mainServer          = root.node("servers", "main").getString("");
            limboServer         = root.node("servers", "limbo").getString("");

            debug               = root.node("debug").getBoolean(false);

            onLoginCommands     = root.node("commands", "on-login").getList(String.class, List.of());
            onRegisterCommands  = root.node("commands", "on-register").getList(String.class, List.of());

        } catch (Exception e) {
            logger.error("Failed to load config.yml, using defaults", e);
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String getMainServer()       { return mainServer; }
    public String getLimboServer()      { return limboServer; }
    public boolean isDebug()            { return debug; }
    public void setDebug(boolean debug) { this.debug = debug; }

    public List<String> getOnLoginCommands()    { return onLoginCommands; }
    public List<String> getOnRegisterCommands() { return onRegisterCommands; }
}
