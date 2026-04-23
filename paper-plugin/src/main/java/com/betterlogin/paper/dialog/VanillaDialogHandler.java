package com.betterlogin.paper.dialog;

import com.betterlogin.paper.BetterLoginBridge;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.TextInput;
import io.papermc.paper.event.player.PlayerDialogRespondEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dialog handler using Minecraft 1.21.6+ native dialog screens.
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>Velocity sends {@code AUTH_REQUIRED} to this bridge.</li>
 *   <li>{@link #supportsDialogs} checks the player's client protocol version via ViaVersion
 *       (soft-depend).  Clients at protocol {@value #DIALOG_MIN_PROTOCOL} or above get the
 *       native Minecraft dialog; older clients receive a plain-text prompt to use
 *       {@code /login} (handled by the already-installed AuthMe plugin).</li>
 *   <li>When a 1.21.6+ player submits the dialog, Paper fires
 *       {@link PlayerDialogRespondEvent} which is caught here and forwarded to Velocity.</li>
 * </ol>
 *
 * <p><strong>Requires:</strong> Paper 1.21.6+ (compiled against 1.21.10-R0.1-SNAPSHOT or
 * newer).  For older clients the fallback path has no extra requirements.</p>
 *
 * <p><strong>Protocol version note:</strong> {@link #DIALOG_MIN_PROTOCOL} is set to the
 * protocol number for Minecraft 1.21.6.  Verify it against
 * <a href="https://wiki.vg/Protocol_version_number">wiki.vg</a> and update the constant if
 * Mojang ever reuses a protocol version across releases.</p>
 */
public class VanillaDialogHandler implements DialogHandler, Listener {

    /**
     * Minimum client protocol version that supports the 1.21.6 native dialog screen.
     * 1.21.4 = 769, 1.21.5 = 770, 1.21.6 = 771.
     * Check <a href="https://wiki.vg/Protocol_version_number">wiki.vg</a> to verify.
     */
    static final int DIALOG_MIN_PROTOCOL = 771;

    private static final String SEP = "\0";

    private final BetterLoginBridge plugin;
    private final Set<UUID> pendingAuth;

    /**
     * Players whose dialog response we are currently waiting for, mapped to whether they
     * are registering ({@code true}) or logging in ({@code false}).
     */
    private final Map<UUID, Boolean> awaitingDialog = new ConcurrentHashMap<>();

    public VanillaDialogHandler(BetterLoginBridge plugin, Set<UUID> pendingAuth) {
        this.plugin = plugin;
        this.pendingAuth = pendingAuth;
        // Self-register as a Bukkit event listener so we catch PlayerDialogRespondEvent.
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
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
    // Paper event listener – dialog response
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL)
    public void onDialogRespond(PlayerDialogRespondEvent event) {
        Player player = event.getPlayer();
        Boolean isRegister = awaitingDialog.remove(player.getUniqueId());
        if (isRegister == null) return; // Not our dialog

        // Retrieve the password the player typed in the "password" text-input field.
        String password = event.getInput("password").orElse("");
        handleResponse(player, password, isRegister);
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
     * fails), we assume the client is running the same version as the server –
     * 1.21.10 supports dialogs, so the result is {@code true}.</p>
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
     * Schedules opening of a native Paper dialog on the main thread.
     *
     * <p>If the API throws at runtime (e.g. on a server version that doesn't yet
     * expose the dialog API), the exception is caught, the player is removed from
     * {@code pendingAuth}, and the plain-text fallback is shown instead.</p>
     */
    private void openNativeDialog(Player player, boolean isRegister) {
        awaitingDialog.put(player.getUniqueId(), isRegister);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                player.openDialog(buildDialog(isRegister));
            } catch (Exception e) {
                plugin.getLogger().warning(
                        "Native dialog unavailable for " + player.getName()
                        + " (" + e.getMessage() + "). Showing text fallback.");
                awaitingDialog.remove(player.getUniqueId());
                pendingAuth.remove(player.getUniqueId());
                sendFallbackMessage(player, isRegister);
            }
        });
    }

    /**
     * Constructs the login or registration native dialog.
     *
     * <p>The "password" text input ID must match the key used in
     * {@link #onDialogRespond} when reading {@code event.getInput("password")}.</p>
     */
    private Dialog buildDialog(boolean isRegister) {
        String titleText = isRegister ? "Create Account" : "Login";
        String bodyText  = isRegister
                ? "Choose a password for your new account."
                : "Enter your password to continue.";
        String buttonText = isRegister ? "Register" : "Login";

        return Dialog.custom()
                .title(Component.text(titleText, NamedTextColor.GOLD))
                .body(Component.text(bodyText, NamedTextColor.GRAY))
                .input(TextInput.text("password")
                        .label(Component.text("Password"))
                        .maxLength(50)
                        .build())
                .submitButton(Component.text(buttonText, NamedTextColor.GREEN))
                .build();
    }

    /**
     * Shown to players whose client is older than 1.21.6 (no native dialog support).
     *
     * <p>AuthMe (already installed) handles the {@code /login} and {@code /register}
     * commands for these players; BetterLogin does not add them to {@code pendingAuth}
     * so AuthMe's own restrictions apply without interference.</p>
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
