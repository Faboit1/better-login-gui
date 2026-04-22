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
 * Enforces restrictions on unauthenticated players:
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
        // Allow /login and /register in case the player prefers chat commands as a fallback
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
        pendingAuth.remove(event.getPlayer().getUniqueId());
    }

    private boolean isPending(Player player) {
        return pendingAuth.contains(player.getUniqueId());
    }
}
