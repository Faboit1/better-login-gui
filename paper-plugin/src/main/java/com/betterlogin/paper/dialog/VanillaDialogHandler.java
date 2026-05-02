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
 *   <li>Velocity sends {@code AUTH_REQUIRED} to this bridge.</li>
 *   <li>{@link #supportsDialogs} checks the player's client protocol version via ViaVersion
 *       (soft-depend).  Clients at protocol {@value #DIALOG_MIN_PROTOCOL} or above get the
 *       native Minecraft dialog; older clients receive a plain-text prompt to use
 *       {@code /login} or {@code /register}, which AuthMe handles directly.</li>
 *   <li>When a 1.21.6+ player clicks the submit button, {@link #handleResponse} dispatches
 *       the appropriate {@code /login} or {@code /register} command as the player; AuthMe
 *       validates the credentials and fires its own events that {@link com.betterlogin.paper.listener.AuthMeListener}
 *       listens to.</li>
 * </ol>
 */
public class VanillaDialogHandler implements DialogHandler {

    /**
     * Minimum client protocol version that supports the 1.21.6 native dialog screen.
     * 1.21.4 = 769, 1.21.5 = 770, 1.21.6 = 771.
     */
    static final int DIALOG_MIN_PROTOCOL = 771;

    /** Ticks to wait before re-showing the dialog after Cancel or a failed attempt (1 second = 20 ticks). */
    private static final long DIALOG_RESHOW_DELAY_TICKS = 20L;

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private final BetterLoginBridge plugin;
    private final Set<UUID> pendingAuth;
    private final PaperConfig config;

    public VanillaDialogHandler(BetterLoginBridge plugin, Set<UUID> pendingAuth, PaperConfig config) {
        this.plugin = plugin;
        this.pendingAuth = pendingAuth;
        this.config = config;
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

    @Override
    public void handleResponse(Player player, String input, boolean isRegister) {
        // Clear the registration-flow tracking entry; pendingAuth stays set until
        // AuthMe fires its LoginEvent/RegisterEvent (handled by AuthMeListener).
        plugin.getPendingRegistration().remove(player.getUniqueId());

        // Dispatch the appropriate AuthMe command on behalf of the player.
        // AuthMe intercepts /login and /register and handles all credential
        // verification, storage, and messaging itself.
        String command = isRegister ? "register " + input.trim() : "login " + input.trim();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.performCommand(command);
            }
        });
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    /**
     * Returns {@code true} if this player's Minecraft client supports the 1.21.6+
     * native dialog screen.
     *
     * <p>If ViaVersion is present, the actual client protocol version is compared
     * against {@link #DIALOG_MIN_PROTOCOL} using reflection (ViaVersion is not a
     * compile-time dependency due to its POM having DOCTYPE declarations that some
     * XML parsers reject).  Without ViaVersion (or if the reflection call fails),
     * we assume the client is running the same version as the server.</p>
     */
    private boolean supportsDialogs(Player player) {
        if (plugin.getServer().getPluginManager().isPluginEnabled("ViaVersion")) {
            try {
                // Equivalent to: com.viaversion.viaversion.api.Via.getAPI().getPlayerVersion(uuid)
                Class<?> viaClass = Class.forName("com.viaversion.viaversion.api.Via");
                Object api = viaClass.getMethod("getAPI").invoke(null);
                int clientProtocol = (int) api.getClass()
                        .getMethod("getPlayerVersion", java.util.UUID.class)
                        .invoke(api, player.getUniqueId());
                return clientProtocol >= DIALOG_MIN_PROTOCOL;
            } catch (Exception ignored) {
                // ViaVersion API call failed – fall through and assume support
            }
        }
        return true;
    }

    /**
     * Builds and shows a native Paper dialog to the player using values from config.
     *
     * <p>If showing the dialog throws at runtime (e.g. missing API on this Paper build),
     * the player is removed from {@code pendingAuth} and the plain-text fallback is shown.</p>
     */
    private void openNativeDialog(Player player, boolean isRegister) {
        UUID uuid = player.getUniqueId();

        // Read all dialog text and colours from config
        Component title = Component.text(
                isRegister ? config.getRegisterTitle()       : config.getLoginTitle(),
                isRegister ? config.getRegisterTitleColor()  : config.getLoginTitleColor());
        Component body = Component.text(
                isRegister ? config.getRegisterBody()        : config.getLoginBody(),
                isRegister ? config.getRegisterBodyColor()   : config.getLoginBodyColor());
        Component submitLabel = Component.text(
                isRegister ? config.getRegisterSubmitButton() : config.getLoginSubmitButton(),
                isRegister ? config.getRegisterSubmitColor()  : config.getLoginSubmitColor());
        Component cancelLabel = Component.text(
                isRegister ? config.getRegisterCancelButton() : config.getLoginCancelButton(),
                isRegister ? config.getRegisterCancelColor()  : config.getLoginCancelColor());
        Component passwordLabel = Component.text(
                isRegister ? config.getRegisterPasswordLabel() : config.getLoginPasswordLabel());
        int maxLength = isRegister ? config.getRegisterMaxPasswordLength() : config.getLoginMaxPasswordLength();
        boolean canClose = isRegister ? config.isRegisterCanCloseWithEscape() : config.isLoginCanCloseWithEscape();

        // Build the submit callback – invoked when the player clicks the submit button.
        DialogActionCallback callback = (response, audience) -> {
            String pw = response.getText("password");
            if (pw == null) pw = "";
            final String password = pw;
            // The callback may fire off the main thread – schedule to be safe.
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Player p = plugin.getServer().getPlayer(uuid);
                if (p != null && pendingAuth.contains(uuid)) {
                    handleResponse(p, password, isRegister);
                }
            });
        };

        // Build the cancel callback – re-shows the dialog after 1 s so the player
        // is never left frozen without a dialog.
        DialogActionCallback cancelCallback = (response, audience) ->
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                Player p = plugin.getServer().getPlayer(uuid);
                if (p != null && pendingAuth.contains(uuid)) {
                    openNativeDialog(p, isRegister);
                }
            }, DIALOG_RESHOW_DELAY_TICKS);

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(title)
                        .body(List.of(DialogBody.plainMessage(body)))
                        .inputs(List.of(
                                DialogInput.text("password", passwordLabel)
                                        .maxLength(maxLength)
                                        .build()))
                        .canCloseWithEscape(canClose)
                        .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(submitLabel)
                                .action(DialogAction.customClick(callback, ClickCallback.Options.builder().build()))
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
     *
     * <p>The player is added to {@code pendingAuth} (so movement/interaction restrictions
     * apply) and to {@code pendingRegistration} (so the {@code /login} and
     * {@code /register} commands know which flow the player is in).</p>
     */
    private void sendFallbackMessage(Player player, boolean isRegister) {
        UUID uuid = player.getUniqueId();
        // Keep player frozen via pendingAuth while they type their password
        pendingAuth.add(uuid);
        // Track whether this player is registering or logging in
        plugin.getPendingRegistration().put(uuid, isRegister);

        String command = isRegister ? "/register <password>" : "/login <password>";
        String chatMsg   = config.getFallbackNoSupportMessage().replace("{command}", command);
        String actionMsg = config.getFallbackActionBarMessage().replace("{command}", command);

        player.sendMessage(LEGACY.deserialize(chatMsg));
        player.sendActionBar(LEGACY.deserialize(actionMsg));
    }

}
