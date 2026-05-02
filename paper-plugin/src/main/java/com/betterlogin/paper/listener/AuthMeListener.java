package com.betterlogin.paper.listener;

import com.betterlogin.paper.BetterLoginBridge;
import com.betterlogin.paper.config.PaperConfig;
import fr.xephi.authme.events.FailedLoginEvent;
import fr.xephi.authme.events.LoginEvent;
import fr.xephi.authme.events.RegisterEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Listens for AuthMe authentication events and updates BetterLogin state accordingly.
 *
 * <p>BetterLogin's only job is to show the dialog GUI; AuthMe handles the actual
 * credential verification.  Once AuthMe fires a {@link LoginEvent} or
 * {@link RegisterEvent}, this listener:
 * <ul>
 *   <li>Removes the player from {@code pendingAuth} (lifting movement/action restrictions).</li>
 *   <li>Removes the boss bar, clears the title.</li>
 *   <li>Sends the welcome message.</li>
 *   <li>Notifies the Velocity proxy via an {@code AUTH_COMPLETE} plugin-channel message
 *       so the proxy can update its auth state and run any configured post-auth commands.</li>
 * </ul>
 *
 * <p>On a {@link FailedLoginEvent}, the dialog is re-shown after a short delay so the
 * player can try again without being permanently frozen.</p>
 *
 * <p>This listener is only registered when AuthMe is present on the server.</p>
 */
public class AuthMeListener implements Listener {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    /** Ticks to wait before re-showing the dialog after a failed attempt (1 s = 20 ticks). */
    private static final long RESHOW_DELAY_TICKS = 20L;

    private final BetterLoginBridge plugin;

    public AuthMeListener(BetterLoginBridge plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLogin(LoginEvent event) {
        handleAuthSuccess(event.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRegister(RegisterEvent event) {
        handleAuthSuccess(event.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFailedLogin(FailedLoginEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!plugin.getPendingAuth().contains(uuid)) return;

        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[DEBUG] AuthMe FailedLoginEvent for " + player.getName()
                    + " – re-showing dialog");
        }

        // Re-show the dialog after a brief delay so the player can try again.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && plugin.getPendingAuth().contains(uuid)) {
                plugin.getDialogHandler().showLoginDialog(player);
            }
        }, RESHOW_DELAY_TICKS);
    }

    // ------------------------------------------------------------------

    private void handleAuthSuccess(Player player, boolean wasRegister) {
        UUID uuid = player.getUniqueId();
        if (!plugin.getPendingAuth().contains(uuid)) return;

        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[DEBUG] AuthMe auth success for " + player.getName()
                    + " (wasRegister=" + wasRegister + ")");
        }

        plugin.getPendingAuth().remove(uuid);
        plugin.getPendingRegistration().remove(uuid);
        plugin.removeBossBar(uuid);
        player.clearTitle();

        PaperConfig cfg = plugin.getPaperConfig();
        String welcomeMsg = cfg.getWelcomeMessage().replace("{player}", player.getName());
        player.sendMessage(LEGACY.deserialize(welcomeMsg));

        // Notify Velocity that authentication is complete so it can update its state
        // and execute any configured post-auth commands.
        String payload = String.join(BetterLoginBridge.SEP,
                "AUTH_COMPLETE",
                uuid.toString(),
                String.valueOf(wasRegister)
        );
        player.sendPluginMessage(plugin, BetterLoginBridge.CHANNEL,
                payload.getBytes(StandardCharsets.UTF_8));
    }
}
