package com.betterlogin.paper.dialog;

import com.betterlogin.paper.BetterLoginBridge;
import com.betterlogin.paper.config.PaperConfig;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Dialog handler using Minecraft 1.21.6+ native dialog screens.
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>Player joins – {@link com.betterlogin.paper.listener.AuthPlayerListener} detects them
 *       via AuthMe and calls {@link #showLoginDialog} or {@link #showRegisterDialog}.</li>
 *   <li>For modern clients (protocol &ge; {@value #DIALOG_MIN_PROTOCOL}) a native dialog is
 *       shown with one password field (login) or two fields (register: password + confirm).</li>
 *   <li>On submit the AuthMe API is called directly:
 *       <ul>
 *         <li>Login: {@code checkPassword} → if correct, {@code forceLogin} → fires
 *             {@code LoginEvent} → {@link com.betterlogin.paper.listener.AuthMeListener} cleans up.</li>
 *         <li>Register: passwords must match, then {@code forceRegister(player, password, true)}
 *             → fires {@code RegisterEvent} → {@link com.betterlogin.paper.listener.AuthMeListener}
 *             cleans up.</li>
 *       </ul>
 *   </li>
 *   <li>If the password is wrong or passwords do not match, the dialog is re-shown after
 *       {@value #DIALOG_RESHOW_DELAY_TICKS} ticks so the player can try again.</li>
 *   <li>Old clients fall back to a chat prompt directing them to type {@code /login} or
 *       {@code /register}, which AuthMe processes directly.</li>
 * </ol>
 */
public class VanillaDialogHandler implements DialogHandler {

    /**
     * Minimum client protocol version that supports the 1.21.6 native dialog screen.
     * 1.21.4 = 769, 1.21.5 = 770, 1.21.6 = 771.
     */
    static final int DIALOG_MIN_PROTOCOL = 771;

    /** Ticks to wait before re-showing the dialog after a failed attempt (1 second = 20 ticks). */
    private static final long DIALOG_RESHOW_DELAY_TICKS = 20L;

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private final BetterLoginBridge plugin;
    private final Set<UUID> pendingAuth;
    private final PaperConfig config;

    public VanillaDialogHandler(BetterLoginBridge plugin, Set<UUID> pendingAuth, PaperConfig config) {
        this.plugin      = plugin;
        this.pendingAuth = pendingAuth;
        this.config      = config;
    }

    // ------------------------------------------------------------------
    // DialogHandler implementation
    // ------------------------------------------------------------------

    @Override
    public void showLoginDialog(Player player) {
        if (supportsDialogs(player)) {
            pendingAuth.add(player.getUniqueId());
            openNativeDialog(player, false);
        } else {
            sendFallbackMessage(player, false);
        }
    }

    @Override
    public void showRegisterDialog(Player player) {
        if (supportsDialogs(player)) {
            pendingAuth.add(player.getUniqueId());
            openNativeDialog(player, true);
        } else {
            sendFallbackMessage(player, true);
        }
    }

    // ------------------------------------------------------------------
    // Dialog construction
    // ------------------------------------------------------------------

    /**
     * Builds and shows a native Paper dialog.
     *
     * <p>Login dialog: one password field.  On submit the AuthMe API verifies the
     * password; on failure the dialog re-appears.<br>
     * Register dialog: password + confirm-password fields.  Both must match before
     * the AuthMe API registers the player.</p>
     *
     * <p>If {@link Player#showDialog} throws (e.g. server running older Paper build),
     * the player is removed from {@code pendingAuth} and the plain-text fallback is shown.</p>
     */
    private void openNativeDialog(Player player, boolean isRegister) {
        UUID uuid = player.getUniqueId();

        // ── Text / colours from config ──────────────────────────────────────────
        Component title = Component.text(
                isRegister ? config.getRegisterTitle()      : config.getLoginTitle(),
                isRegister ? config.getRegisterTitleColor() : config.getLoginTitleColor());
        Component body = Component.text(
                isRegister ? config.getRegisterBody()      : config.getLoginBody(),
                isRegister ? config.getRegisterBodyColor() : config.getLoginBodyColor());
        Component submitLabel = Component.text(
                isRegister ? config.getRegisterSubmitButton() : config.getLoginSubmitButton(),
                isRegister ? config.getRegisterSubmitColor()  : config.getLoginSubmitColor());
        Component cancelLabel = Component.text(
                isRegister ? config.getRegisterCancelButton() : config.getLoginCancelButton(),
                isRegister ? config.getRegisterCancelColor()  : config.getLoginCancelColor());
        Component passwordLabel = Component.text(
                isRegister ? config.getRegisterPasswordLabel() : config.getLoginPasswordLabel());
        int     maxLength = isRegister ? config.getRegisterMaxPasswordLength() : config.getLoginMaxPasswordLength();
        boolean canClose  = isRegister ? config.isRegisterCanCloseWithEscape() : config.isLoginCanCloseWithEscape();

        // ── Input fields ────────────────────────────────────────────────────────
        List<DialogInput> inputs;
        if (isRegister) {
            Component confirmLabel = Component.text(config.getRegisterConfirmPasswordLabel());
            inputs = List.of(
                    DialogInput.text("password",         passwordLabel).initial("").maxLength(maxLength).build(),
                    DialogInput.text("confirm-password", confirmLabel) .initial("").maxLength(maxLength).build()
            );
        } else {
            inputs = List.of(
                    DialogInput.text("password", passwordLabel).initial("").maxLength(maxLength).build()
            );
        }

        // ── Submit callback ─────────────────────────────────────────────────────
        DialogActionCallback submitCallback = (response, audience) -> {
            String pw = response.getText("password");
            if (pw == null) pw = "";
            final String password = pw;

            if (isRegister) {
                String conf = response.getText("confirm-password");
                if (conf == null) conf = "";
                final String confirm = conf;

                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    Player p = plugin.getServer().getPlayer(uuid);
                    if (p == null || !pendingAuth.contains(uuid)) return;

                    if (!password.equals(confirm)) {
                        // Passwords don't match – re-show dialog
                        plugin.getServer().getScheduler().runTaskLater(plugin,
                                () -> { if (p.isOnline() && pendingAuth.contains(uuid)) openNativeDialog(p, true); },
                                DIALOG_RESHOW_DELAY_TICKS);
                        return;
                    }
                    // Register via AuthMe API (auto-logs the player in)
                    authMeForceRegister(p, password);
                });
            } else {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    Player p = plugin.getServer().getPlayer(uuid);
                    if (p == null || !pendingAuth.contains(uuid)) return;

                    if (authMeCheckPassword(p.getName(), password)) {
                        // Password correct – let AuthMe log the player in
                        authMeForceLogin(p);
                    } else {
                        // Wrong password – re-show dialog
                        plugin.getServer().getScheduler().runTaskLater(plugin,
                                () -> { if (p.isOnline() && pendingAuth.contains(uuid)) openNativeDialog(p, false); },
                                DIALOG_RESHOW_DELAY_TICKS);
                    }
                });
            }
        };

        // ── Cancel callback – re-shows dialog so the player cannot escape auth ──
        DialogActionCallback cancelCallback = (response, audience) ->
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                Player p = plugin.getServer().getPlayer(uuid);
                if (p != null && pendingAuth.contains(uuid)) openNativeDialog(p, isRegister);
            }, DIALOG_RESHOW_DELAY_TICKS);

        // ── Build and show ──────────────────────────────────────────────────────
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(title)
                        .body(List.of(DialogBody.plainMessage(body)))
                        .inputs(inputs)
                        .canCloseWithEscape(canClose)
                        .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(submitLabel)
                                .action(DialogAction.customClick(submitCallback, ClickCallback.Options.builder().build()))
                                .build(),
                        ActionButton.builder(cancelLabel)
                                .action(DialogAction.customClick(cancelCallback, ClickCallback.Options.builder().build()))
                                .build()
                )));

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                player.showDialog(dialog);
            } catch (Exception e) {
                plugin.getLogger().warning(
                        "Native dialog unavailable for " + player.getName()
                        + " (" + e.getMessage() + "). Showing text fallback.");
                pendingAuth.remove(uuid);
                sendFallbackMessage(player, isRegister);
            }
        });
    }

    /**
     * Shown to players whose client does not support the 1.21.6+ native dialog.
     * They must type {@code /login} or {@code /register} themselves; AuthMe handles it.
     */
    private void sendFallbackMessage(Player player, boolean isRegister) {
        pendingAuth.add(player.getUniqueId());
        String command  = isRegister ? "/register <password> <confirm-password>" : "/login <password>";
        String chatMsg  = config.getFallbackNoSupportMessage().replace("{command}", command);
        String barMsg   = config.getFallbackActionBarMessage().replace("{command}", command);
        player.sendMessage(LEGACY.deserialize(chatMsg));
        player.sendActionBar(LEGACY.deserialize(barMsg));
    }

    // ------------------------------------------------------------------
    // Version check
    // ------------------------------------------------------------------

    /**
     * Returns {@code true} if this player's client supports the 1.21.6+ native dialog.
     * Uses ViaVersion (soft-depend) when present to get the actual client protocol version;
     * otherwise assumes the client matches the server version.
     */
    private boolean supportsDialogs(Player player) {
        if (plugin.getServer().getPluginManager().isPluginEnabled("ViaVersion")) {
            try {
                Class<?> viaClass = Class.forName("com.viaversion.viaversion.api.Via");
                Object api = viaClass.getMethod("getAPI").invoke(null);
                int clientProtocol = (int) api.getClass()
                        .getMethod("getPlayerVersion", java.util.UUID.class)
                        .invoke(api, player.getUniqueId());
                return clientProtocol >= DIALOG_MIN_PROTOCOL;
            } catch (Exception ignored) {
                // ViaVersion call failed – assume support
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // AuthMe API (accessed via reflection – no compile-time dependency)
    // ------------------------------------------------------------------

    /**
     * Verifies a player's password against the AuthMe database.
     *
     * @return {@code true} if the password matches, {@code false} otherwise or on error
     */
    private boolean authMeCheckPassword(String playerName, String password) {
        try {
            Class<?> apiClass = Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            if (api == null) return false;
            return (boolean) apiClass.getMethod("checkPassword", String.class, String.class)
                    .invoke(api, playerName, password);
        } catch (Exception e) {
            plugin.getLogger().warning("AuthMe checkPassword failed for " + playerName + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Forces AuthMe to log the player in without further password verification.
     * Fires AuthMe's {@code LoginEvent}, which {@link com.betterlogin.paper.listener.AuthMeListener}
     * listens to for cleanup.
     */
    private void authMeForceLogin(Player player) {
        try {
            Class<?> apiClass = Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            if (api == null) return;
            apiClass.getMethod("forceLogin", Player.class).invoke(api, player);
        } catch (Exception e) {
            plugin.getLogger().warning("AuthMe forceLogin failed for " + player.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Registers a new player via the AuthMe API and auto-logs them in.
     * Fires AuthMe's {@code RegisterEvent} (and subsequently {@code LoginEvent}),
     * which {@link com.betterlogin.paper.listener.AuthMeListener} listens to for cleanup.
     *
     * @param player   the online player to register
     * @param password the plain-text password chosen by the player
     */
    private void authMeForceRegister(Player player, String password) {
        try {
            Class<?> apiClass = Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            if (api == null) return;
            // forceRegister(Player, String, boolean autoLogin)
            apiClass.getMethod("forceRegister", Player.class, String.class, boolean.class)
                    .invoke(api, player, password, true);
        } catch (Exception e) {
            plugin.getLogger().warning("AuthMe forceRegister failed for " + player.getName() + ": " + e.getMessage());
        }
    }
}

