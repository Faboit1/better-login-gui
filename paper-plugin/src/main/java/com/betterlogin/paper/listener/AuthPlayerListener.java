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
 * Enforces restrictions on unauthenticated players and triggers the auth dialog on join.
 *
 * <h2>Join flow</h2>
 * <ol>
 *   <li>Player joins Paper &rarr; {@link #onJoin} fires.</li>
 *   <li>AuthMe is queried to determine if the player is registered (login) or new (register).</li>
 *   <li>The dialog is shown directly after a short delay to ensure the client is ready.</li>
 *   <li>{@code PLAYER_READY} is also sent to Velocity (if present) for server routing;
 *       if Velocity replies with {@code AUTH_REQUIRED}, {@link BridgeMessageListener} will
 *       skip it because the player is already in {@code pendingAuth}.</li>
 * </ol>
 *
 * <h2>Restrictions</h2>
 * <ul>
 *   <li>Block movement, interaction, inventory, chat, and commands.</li>
 *   <li>Allow {@code /login} and {@code /register} for fallback (old-client) players.</li>
 * </ul>
 */
public class AuthPlayerListener implements Listener {

    private static final long JOIN_DELAY_TICKS = 10L; // 0.5 s - let the client finish loading

    private final BetterLoginBridge plugin;
    private final DialogHandler dialogHandler;
    private final Set<UUID> pendingAuth;

    public AuthPlayerListener(BetterLoginBridge plugin, DialogHandler dialogHandler,
                               Set<UUID> pendingAuth) {
        this.plugin        = plugin;
        this.dialogHandler = dialogHandler;
        this.pendingAuth   = pendingAuth;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // If AUTH_REQUIRED already arrived before PlayerJoinEvent (very fast Velocity response),
        // honour that request immediately and skip the local AuthMe lookup.
        Boolean preQueued = plugin.getPendingDialogRequests().remove(uuid);
        if (preQueued != null) {
            final boolean np = preQueued;
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> { if (player.isOnline()) plugin.showAuthDialog(player, np); },
                    JOIN_DELAY_TICKS);
            return;
        }

        // Check AuthMe registration status locally (source of truth).
        boolean authMeRegistered = plugin.isAuthMeRegistered(player);
        boolean isNewPlayer      = !authMeRegistered;

        // Notify Velocity (if present) for server routing. No-op if Velocity isn't connected.
        final byte[] payload = (BetterLoginBridge.MSG_PLAYER_READY
                + BetterLoginBridge.SEP + uuid
                + BetterLoginBridge.SEP + authMeRegistered)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;

            player.sendPluginMessage(plugin, BetterLoginBridge.CHANNEL, payload);
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("[DEBUG] -> PLAYER_READY: player="
                        + player.getName() + " authMeRegistered=" + authMeRegistered);
            }

            // Show dialog directly (standalone mode). BridgeMessageListener will skip
            // any duplicate AUTH_REQUIRED from Velocity if the player is already pending.
            if (!pendingAuth.contains(uuid)) {
                plugin.showAuthDialog(player, isNewPlayer);
            }
        }, JOIN_DELAY_TICKS);
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
        // Allow /login and /register so fallback (old-client) players can authenticate
        String cmd = event.getMessage().toLowerCase();
        if (!cmd.startsWith("/login") && !cmd.startsWith("/register")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p && isPending(p)) event.setCancelled(true);
        if (event.getEntity()  instanceof Player p && isPending(p)) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        pendingAuth.remove(uuid);
        plugin.getPendingDialogRequests().remove(uuid);
    }

    private boolean isPending(Player player) {
        return pendingAuth.contains(player.getUniqueId());
    }
}
