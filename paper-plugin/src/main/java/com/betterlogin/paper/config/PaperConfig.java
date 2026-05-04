package com.betterlogin.paper.config;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads and exposes all configurable options from the Paper plugin's config.yml.
 *
 * <p>Dialog text, colours and behaviour, and fallback messages are all controlled from
 * config.  Named Adventure colours (e.g. {@code GOLD}, {@code RED}) are accepted
 * case-insensitively; hex codes ({@code #RRGGBB}) are also supported for text colours.</p>
 */
public class PaperConfig {

    private final JavaPlugin plugin;

    // -- login dialog ----------------------------------------------------------
    private String loginTitle;
    private TextColor loginTitleColor;
    private String loginBody;
    private TextColor loginBodyColor;
    private String loginSubmitButton;
    private TextColor loginSubmitColor;
    private String loginCancelButton;
    private TextColor loginCancelColor;
    private String loginPasswordLabel;
    private int loginMaxPasswordLength;
    private boolean loginCanCloseWithEscape;

    // -- register dialog -------------------------------------------------------
    private String registerTitle;
    private TextColor registerTitleColor;
    private String registerBody;
    private TextColor registerBodyColor;
    private String registerSubmitButton;
    private TextColor registerSubmitColor;
    private String registerCancelButton;
    private TextColor registerCancelColor;
    private String registerPasswordLabel;
    private String registerConfirmPasswordLabel;
    private int registerMaxPasswordLength;
    private boolean registerCanCloseWithEscape;

    // -- fallback (older clients) ----------------------------------------------
    private String fallbackNoSupportMessage;
    private String fallbackActionBarMessage;

    // -- misc ------------------------------------------------------------------
    private String welcomeMessage;

    public PaperConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Re-reads all values from the currently loaded {@link FileConfiguration}. */
    public void reload() {
        FileConfiguration cfg = plugin.getConfig();

        // Login dialog
        loginTitle              = cfg.getString( "dialog.login.title",                 "Login");
        loginTitleColor         = parseColor(cfg.getString("dialog.login.title-color",  "GOLD"),  NamedTextColor.GOLD);
        loginBody               = cfg.getString( "dialog.login.body",                  "Enter your password to continue.");
        loginBodyColor          = parseColor(cfg.getString("dialog.login.body-color",   "GRAY"),  NamedTextColor.GRAY);
        loginSubmitButton       = cfg.getString( "dialog.login.submit-button",         "Login");
        loginSubmitColor        = parseColor(cfg.getString("dialog.login.submit-color", "GREEN"), NamedTextColor.GREEN);
        loginCancelButton       = cfg.getString( "dialog.login.cancel-button",         "Cancel");
        loginCancelColor        = parseColor(cfg.getString("dialog.login.cancel-color", "RED"),   NamedTextColor.RED);
        loginPasswordLabel      = cfg.getString( "dialog.login.password-label",        "Password");
        loginMaxPasswordLength  = cfg.getInt(    "dialog.login.max-password-length",   50);
        loginCanCloseWithEscape = cfg.getBoolean("dialog.login.can-close-with-escape", false);

        // Register dialog
        registerTitle                = cfg.getString( "dialog.register.title",                  "Create Account");
        registerTitleColor           = parseColor(cfg.getString("dialog.register.title-color",   "GOLD"),  NamedTextColor.GOLD);
        registerBody                 = cfg.getString( "dialog.register.body",                   "Choose a password for your new account.");
        registerBodyColor            = parseColor(cfg.getString("dialog.register.body-color",    "GRAY"),  NamedTextColor.GRAY);
        registerSubmitButton         = cfg.getString( "dialog.register.submit-button",          "Register");
        registerSubmitColor          = parseColor(cfg.getString("dialog.register.submit-color",  "GREEN"), NamedTextColor.GREEN);
        registerCancelButton         = cfg.getString( "dialog.register.cancel-button",          "Cancel");
        registerCancelColor          = parseColor(cfg.getString("dialog.register.cancel-color",  "RED"),   NamedTextColor.RED);
        registerPasswordLabel        = cfg.getString( "dialog.register.password-label",         "Password");
        registerConfirmPasswordLabel = cfg.getString( "dialog.register.confirm-password-label", "Confirm Password");
        registerMaxPasswordLength    = cfg.getInt(    "dialog.register.max-password-length",    50);
        registerCanCloseWithEscape   = cfg.getBoolean("dialog.register.can-close-with-escape",  false);

        // Fallback
        fallbackNoSupportMessage = cfg.getString("dialog.fallback.no-support-message",
                "&eYour client does not support dialog screens. Please type {command} to authenticate.");
        fallbackActionBarMessage = cfg.getString("dialog.fallback.action-bar-message",
                "&eType {command} to authenticate");

        // Misc
        welcomeMessage = cfg.getString("messages.welcome", "&aWelcome, {player}!");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Parses a text colour from config.  Accepts named Adventure colours (case-insensitive,
     * e.g. {@code GOLD}) or hex strings ({@code #RRGGBB}).  Returns {@code fallback} when
     * the value is blank or unrecognised.
     */
    private TextColor parseColor(String value, TextColor fallback) {
        if (value == null || value.isBlank()) return fallback;
        if (value.startsWith("#")) {
            TextColor hex = TextColor.fromHexString(value);
            return hex != null ? hex : fallback;
        }
        TextColor named = NamedTextColor.NAMES.value(value.toLowerCase());
        return named != null ? named : fallback;
    }

    // -------------------------------------------------------------------------
    // Accessors - login dialog
    // -------------------------------------------------------------------------

    public String getLoginTitle()              { return loginTitle; }
    public TextColor getLoginTitleColor()      { return loginTitleColor; }
    public String getLoginBody()               { return loginBody; }
    public TextColor getLoginBodyColor()       { return loginBodyColor; }
    public String getLoginSubmitButton()       { return loginSubmitButton; }
    public TextColor getLoginSubmitColor()     { return loginSubmitColor; }
    public String getLoginCancelButton()       { return loginCancelButton; }
    public TextColor getLoginCancelColor()     { return loginCancelColor; }
    public String getLoginPasswordLabel()      { return loginPasswordLabel; }
    public int getLoginMaxPasswordLength()     { return loginMaxPasswordLength; }
    public boolean isLoginCanCloseWithEscape() { return loginCanCloseWithEscape; }

    // -------------------------------------------------------------------------
    // Accessors - register dialog
    // -------------------------------------------------------------------------

    public String getRegisterTitle()                { return registerTitle; }
    public TextColor getRegisterTitleColor()        { return registerTitleColor; }
    public String getRegisterBody()                 { return registerBody; }
    public TextColor getRegisterBodyColor()         { return registerBodyColor; }
    public String getRegisterSubmitButton()         { return registerSubmitButton; }
    public TextColor getRegisterSubmitColor()       { return registerSubmitColor; }
    public String getRegisterCancelButton()         { return registerCancelButton; }
    public TextColor getRegisterCancelColor()       { return registerCancelColor; }
    public String getRegisterPasswordLabel()        { return registerPasswordLabel; }
    public String getRegisterConfirmPasswordLabel() { return registerConfirmPasswordLabel; }
    public int getRegisterMaxPasswordLength()       { return registerMaxPasswordLength; }
    public boolean isRegisterCanCloseWithEscape()   { return registerCanCloseWithEscape; }

    // -------------------------------------------------------------------------
    // Accessors - fallback / misc
    // -------------------------------------------------------------------------

    public String getFallbackNoSupportMessage() { return fallbackNoSupportMessage; }
    public String getFallbackActionBarMessage() { return fallbackActionBarMessage; }
    public String getWelcomeMessage()           { return welcomeMessage; }
}
