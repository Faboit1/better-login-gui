package com.betterlogin.paper.dialog;

import com.betterlogin.paper.BetterLoginBridge;
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
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
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
 *       {@code /login} or {@code /register} (handled by the already-installed AuthMe plugin).</li>
 *   <li>When a 1.21.6+ player clicks the submit button, the {@link io.papermc.paper.registry.data.dialog.action.DialogActionCallback}
 *       registered on that button fires and forwards the response to Velocity.</li>
 * </ol>
 *
 * <h2>API reference</h2>
 * <ul>
 *   <li>Dialog builder: {@link Dialog#create}</li>
 *   <li>Text input: {@link DialogInput#text(String, Component)}</li>
 *   <li>Button callback: {@link DialogAction#customClick(io.papermc.paper.registry.data.dialog.action.DialogActionCallback, ClickCallback.Options)}</li>
 *   <li>Show dialog: {@code player.showDialog(dialog)} (from {@link net.kyori.adventure.audience.Audience})</li>
 *   <li>Read response: {@link io.papermc.paper.dialog.DialogResponseView#getText(String)}</li>
 * </ul>
 *
 * <p><strong>Protocol version note:</strong> {@link #DIALOG_MIN_PROTOCOL} is the protocol
 * number for Minecraft 1.21.6.  Verify against
 * <a href="https://wiki.vg/Protocol_version_number">wiki.vg</a> if Mojang renumbers.</p>
 */
public class VanillaDialogHandler implements DialogHandler {

    /**
     * Minimum client protocol version that supports the 1.21.6 native dialog screen.
     * 1.21.4 = 769, 1.21.5 = 770, 1.21.6 = 771.
     */
    static final int DIALOG_MIN_PROTOCOL = 771;

    private static final String SEP = "\0";

    private final BetterLoginBridge plugin;
    private final Set<UUID> pendingAuth;

    public VanillaDialogHandler(BetterLoginBridge plugin, Set<UUID> pendingAuth) {
        this.plugin = plugin;
        this.pendingAuth = pendingAuth;
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
        pendingAuth.remove(player.getUniqueId());
        forwardToVelocity(player, input.trim(), isRegister);
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    /**
     * Returns {@code true} if this player's Minecraft client supports the 1.21.6+
     * native dialog screen.
     *
     * <p>If ViaVersion is present, the actual client protocol version is compared
     * against {@link #DIALOG_MIN_PROTOCOL}.  Without ViaVersion (or if the look-up
     * fails), we assume the client is running the same version as the server (1.21.10
     * supports dialogs, so the result is {@code true}).</p>
     */
    private boolean supportsDialogs(Player player) {
        if (plugin.getServer().getPluginManager().isPluginEnabled("ViaVersion")) {
            try {
                int clientProtocol = com.viaversion.viaversion.api.Via.getAPI()
                        .getPlayerVersion(player.getUniqueId());
                return clientProtocol >= DIALOG_MIN_PROTOCOL;
            } catch (Exception ignored) {
                // ViaVersion API call failed – fall through and assume support
            }
        }
        return true;
    }

    /**
     * Builds and shows a native Paper dialog to the player.
     *
     * <p>The submit button registers a {@link io.papermc.paper.registry.data.dialog.action.DialogActionCallback}
     * closure.  When the player clicks it, the password field value is read from
     * {@link io.papermc.paper.dialog.DialogResponseView#getText(String)} and forwarded to Velocity.</p>
     *
     * <p>If showing the dialog throws at runtime (e.g. missing API on this Paper build),
     * the player is removed from {@code pendingAuth} and the plain-text fallback is shown.</p>
     */
    private void openNativeDialog(Player player, boolean isRegister) {
        UUID uuid = player.getUniqueId();

        String titleText  = isRegister ? "Create Account" : "Login";
        String bodyText   = isRegister
                ? "Choose a password for your new account."
                : "Enter your password to continue.";
        String submitText = isRegister ? "Register" : "Login";

        // Build the callback closure – invoked when the player clicks the submit button.
        // Captures uuid and isRegister so no state map is needed.
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

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(titleText, NamedTextColor.GOLD))
                        .body(List.of(DialogBody.plainMessage(
                                Component.text(bodyText, NamedTextColor.GRAY))))
                        .inputs(List.of(
                                DialogInput.text("password", Component.text("Password"))
                                        .maxLength(50)
                                        .build()))
                        .canCloseWithEscape(false)
                        .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text(submitText, NamedTextColor.GREEN))
                                .action(DialogAction.customClick(callback, ClickCallback.Options.builder().build()))
                                .build(),
                        ActionButton.builder(Component.text("Cancel", NamedTextColor.RED))
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
     * Shown to players whose client is older than 1.21.6 (no native dialog support).
     *
     * <p>AuthMe (already installed) handles {@code /login} and {@code /register} for
     * these players.  BetterLogin does not add them to {@code pendingAuth} so AuthMe's
     * own restrictions apply without interference.</p>
     */
    private void sendFallbackMessage(Player player, boolean isRegister) {
        String command = isRegister
                ? "/register <password> <confirmPassword>"
                : "/login <password>";

        player.sendMessage(Component.text()
                .append(Component.text(
                        "Your client does not support dialog screens. ",
                        NamedTextColor.YELLOW))
                .append(Component.text("Please type ", NamedTextColor.YELLOW))
                .append(Component.text(command, NamedTextColor.AQUA))
                .append(Component.text(" to authenticate.", NamedTextColor.YELLOW))
                .build());

        player.sendActionBar(Component.text("Type " + command + " to authenticate",
                NamedTextColor.YELLOW));
    }

    private void forwardToVelocity(Player player, String password, boolean isRegister) {
        String payload = String.join(SEP,
                "AUTH_ATTEMPT",
                player.getUniqueId().toString(),
                player.getName(),
                String.valueOf(isRegister),
                password
        );
        player.sendPluginMessage(plugin, BetterLoginBridge.CHANNEL,
                payload.getBytes(StandardCharsets.UTF_8));
    }
}

