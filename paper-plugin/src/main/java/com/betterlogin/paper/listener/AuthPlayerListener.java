package com.betterlogin.paper.listener;

import com.betterlogin.paper.BetterLoginBridge;
import com.betterlogin.paper.dialog.DialogHandler;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;

import java.util.Set;
import java.util.UUID;

/**
 * Enforces restrictions on unauthenticated players and handles the dialog timing fix.
 *
 * <h2>Timing fix</h2>
 * <p>Velocity sends {@code AUTH_REQUIRED} as soon as a player connects to the backend.
 * Paper may receive that plugin message before {@code PlayerJoinEvent} fires, at which
 * point {@code player.showDialog()} silently fails because the client is not fully
 * spawned.  {@link BridgeMessageListener} stores the pending request in
 * {@link BetterLoginBridge#getPendingDialogRequests()}; this listener picks it up in
 * {@link #onJoin} once the player is guaranteed to be fully in the world.</p>
 *
 * <h2>Restrictions</h2>
 * <ul>
 *   <li>Block movement, interaction, inventory, chat, and commands.</li>
 *   <li>Re-open the dialog if the player somehow closes it without submitting.</li>
 * </ul>
 */
public class AuthPlayerListener implements Listener {

    private final BetterLoginBridge plugin;
    private final DialogHandler dialogHandler;
    private final Set<UUID> pendingAuth;

    public AuthPlayerListener(BetterLoginBridge plugin, DialogHandler dialogHandler,
                              Set<UUID> pendingAuth) {
        this.plugin = plugin;
        this.dialogHandler = dialogHandler;
        this.pendingAuth = pendingAuth;
    }

    /**
     * Timing-fix handler: Velocity sends AUTH_REQUIRED ~50 ms after ServerConnectedEvent, but
     * Paper's PluginMessageListener only delivers messages once the player is fully in-game.
     * The message was silently dropped before PlayerJoinEvent fired.
     *
     * <p><b>New flow (two-phase handshake):</b>
     * <ol>
     *   <li>Player joins Paper → this handler fires.</li>
     *   <li>If AUTH_REQUIRED already arrived (legacy/fallback path), handle it immediately.</li>
     *   <li>Otherwise, send {@code PLAYER_READY} to Velocity so it knows the player is ready.
     *       Velocity will respond with {@code AUTH_REQUIRED} or {@code AUTH_SUCCESS} and the
     *       plugin message will now arrive while the player is definitely online.</li>
     * </ol>
     * </p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Legacy fallback: AUTH_REQUIRED arrived before PlayerJoinEvent (e.g. very fast server)
        Boolean isNewPlayer = plugin.getPendingDialogRequests().remove(uuid);
        if (isNewPlayer != null) {
            final boolean np = isNewPlayer;
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> { if (player.isOnline()) plugin.showAuthEffectsAndDialog(player, np); },
                    5L);
            return;
        }

        // Primary path: tell Velocity we are ready so it sends AUTH_REQUIRED/AUTH_SUCCESS now.
        // Include AuthMe registration status so Velocity knows login vs register.
        boolean authMeRegistered = plugin.isAuthMeRegistered(player);
        final byte[] payload = (BetterLoginBridge.MSG_PLAYER_READY
                + BetterLoginBridge.SEP + uuid
                + BetterLoginBridge.SEP + authMeRegistered)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.sendPluginMessage(plugin, BetterLoginBridge.CHANNEL, payload);
                if (plugin.getConfig().getBoolean("debug", false)) {
                    plugin.getLogger().info("[DEBUG] → Velocity PLAYER_READY: player="
                            + player.getName() + " authMeRegistered=" + authMeRegistered);
                }
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        if (!isPending(event.getPlayer())) return;
        // Allow head rotation but block positional movement
        if (event.getFrom().getBlockX() != event.getTo().getBlockX()
            || event.getFrom().getBlockY() != event.getTo().getBlockY()
            || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (isPending(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (isPending((Player) event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isPending(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isPending(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (isPending(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!isPending(event.getPlayer())) return;
        // Allow /login and /register for fallback authentication
        String cmd = event.getMessage().toLowerCase();
        if (!cmd.startsWith("/login") && !cmd.startsWith("/register")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p && isPending(p)) {
            event.setCancelled(true);
        }
        if (event.getEntity() instanceof Player p && isPending(p)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        pendingAuth.remove(uuid);
        plugin.getPendingDialogRequests().remove(uuid);
        plugin.getPendingRegistration().remove(uuid);
        plugin.removeBossBar(uuid);
    }

    private boolean isPending(Player player) {
        return pendingAuth.contains(player.getUniqueId());
    }
}
