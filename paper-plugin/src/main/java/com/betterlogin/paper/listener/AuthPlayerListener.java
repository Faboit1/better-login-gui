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
     * Timing-fix handler: if Velocity sent AUTH_REQUIRED before this player fully spawned,
     * the request is held in {@code pendingDialogRequests} and shown here after the player
     * is guaranteed to be in the world.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        Boolean isNewPlayer = plugin.getPendingDialogRequests().remove(uuid);
        if (isNewPlayer == null) return; // No pending request – normal join

        // Run 1 tick later so the client finishes loading the world before the dialog appears
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> { if (player.isOnline()) plugin.showAuthEffectsAndDialog(player, isNewPlayer); },
                1L);
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
