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

    private boolean sessionEnabled;
    private long sessionMaxAgeSeconds;

    private int maxLoginAttempts;
    private long authTimeoutSeconds;

    private String msgLoginPrompt;
    private String msgRegisterPrompt;
    private String msgRegisterSuccess;
    private String msgLoginSuccess;
    private String msgLoginFailed;
    private String msgAlreadyRegistered;
    private String msgPasswordTooShort;
    private String msgKicked;
    private String msgTimeout;

    private int minPasswordLength;

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

            mainServer          = root.node("servers", "main").getString("main");
            limboServer         = root.node("servers", "limbo").getString("");

            debug               = root.node("debug").getBoolean(false);

            sessionEnabled      = root.node("session", "enabled").getBoolean(true);
            sessionMaxAgeSeconds = root.node("session", "max-age-seconds").getLong(86400);

            maxLoginAttempts    = root.node("security", "max-login-attempts").getInt(5);
            authTimeoutSeconds  = root.node("security", "auth-timeout-seconds").getLong(60);
            minPasswordLength   = root.node("security", "min-password-length").getInt(6);

            msgLoginPrompt      = root.node("messages", "login-prompt").getString("&aEnter your password.");
            msgRegisterPrompt   = root.node("messages", "register-prompt").getString("&aCreate a password.");
            msgRegisterSuccess  = root.node("messages", "register-success").getString("&aRegistration successful!");
            msgLoginSuccess     = root.node("messages", "login-success").getString("&aLogged in successfully!");
            msgLoginFailed      = root.node("messages", "login-failed").getString("&cWrong password. Try again.");
            msgAlreadyRegistered = root.node("messages", "already-registered").getString("&cYou are already registered. Use /login.");
            msgPasswordTooShort = root.node("messages", "password-too-short").getString("&cPassword must be at least {min} characters.");
            msgKicked           = root.node("messages", "kicked").getString("&cToo many failed attempts.");
            msgTimeout          = root.node("messages", "timeout").getString("&cAuthentication timed out.");

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
    public boolean isSessionEnabled()   { return sessionEnabled; }
    public long getSessionMaxAgeSeconds() { return sessionMaxAgeSeconds; }
    public int getMaxLoginAttempts()    { return maxLoginAttempts; }
    public long getAuthTimeoutSeconds() { return authTimeoutSeconds; }
    public int getMinPasswordLength()   { return minPasswordLength; }

    public String getMsgLoginPrompt()      { return msgLoginPrompt; }
    public String getMsgRegisterPrompt()   { return msgRegisterPrompt; }
    public String getMsgRegisterSuccess()  { return msgRegisterSuccess; }
    public String getMsgLoginSuccess()     { return msgLoginSuccess; }
    public String getMsgLoginFailed()      { return msgLoginFailed; }
    public String getMsgAlreadyRegistered(){ return msgAlreadyRegistered; }
    public String getMsgPasswordTooShort() { return msgPasswordTooShort.replace("{min}", String.valueOf(minPasswordLength)); }
    public String getMsgKicked()           { return msgKicked; }
    public String getMsgTimeout()          { return msgTimeout; }

    public List<String> getOnLoginCommands()    { return onLoginCommands; }
    public List<String> getOnRegisterCommands() { return onRegisterCommands; }
}
