package com.betterlogin.paper.listener;

import com.betterlogin.paper.BetterLoginBridge;
import com.betterlogin.paper.config.PaperConfig;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Listens for AuthMe authentication events and updates BetterLogin state accordingly.
 *
 * <p>Events are registered via Bukkit's {@code PluginManager.registerEvent()} with
 * reflection so that no compile-time dependency on AuthMe is required.  AuthMe is a
 * soft-depend; if it is not on the server this class simply does nothing.</p>
 *
 * <p>Call {@link #register(BetterLoginBridge)} after confirming that AuthMe is enabled.</p>
 */
public class AuthMeListener implements Listener {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    /** Ticks to wait before re-showing the dialog after a failed attempt (1 s = 20 ticks). */
    private static final long RESHOW_DELAY_TICKS = 20L;

    private final BetterLoginBridge plugin;

    private AuthMeListener(BetterLoginBridge plugin) {
        this.plugin = plugin;
    }

    /**
     * Registers AuthMe event handlers using Bukkit's reflection-based event API.
     * No AuthMe classes are referenced at compile time.
     *
     * @return {@code true} if all three event types were found and registered
     */
    public static boolean register(BetterLoginBridge plugin) {
        try {
            Class<? extends Event> loginEvent =
                    Class.forName("fr.xephi.authme.events.LoginEvent").asSubclass(Event.class);
            Class<? extends Event> registerEvent =
                    Class.forName("fr.xephi.authme.events.RegisterEvent").asSubclass(Event.class);
            Class<? extends Event> failedLoginEvent =
                    Class.forName("fr.xephi.authme.events.FailedLoginEvent").asSubclass(Event.class);

            AuthMeListener listener = new AuthMeListener(plugin);

            plugin.getServer().getPluginManager().registerEvent(
                    loginEvent, listener, EventPriority.MONITOR,
                    (l, event) -> listener.handleAuthSuccess(getPlayer(event), false),
                    plugin, true);

            plugin.getServer().getPluginManager().registerEvent(
                    registerEvent, listener, EventPriority.MONITOR,
                    (l, event) -> listener.handleAuthSuccess(getPlayer(event), true),
                    plugin, true);

            plugin.getServer().getPluginManager().registerEvent(
                    failedLoginEvent, listener, EventPriority.MONITOR,
                    (l, event) -> listener.onFailedLogin(getPlayer(event)),
                    plugin, false);

            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------

    private void onFailedLogin(Player player) {
        if (player == null) return;
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

    private void handleAuthSuccess(Player player, boolean wasRegister) {
        if (player == null) return;
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

    /** Extracts the player from an AuthMe event via reflection (all AuthMe events extend PlayerEvent). */
    private static Player getPlayer(Event event) {
        try {
            Method m = event.getClass().getMethod("getPlayer");
            return (Player) m.invoke(event);
        } catch (Exception e) {
            return null;
        }
    }
}
