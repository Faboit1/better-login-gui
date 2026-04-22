package com.betterlogin.paper.dialog;

import com.betterlogin.paper.BetterLoginBridge;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import com.github.retrooper.packetevents.util.Vector3i;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dialog handler that uses the vanilla sign-editor screen to collect a password.
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>A fake oak sign block is shown to the player at a position below their feet
 *       (client-side only, no block is modified on the server).</li>
 *   <li>The sign-editor screen opens immediately.</li>
 *   <li>The player types their password on line 0 and clicks "Done".</li>
 *   <li>The {@code UPDATE_SIGN} packet is intercepted by the PacketEvents listener;
 *       the block change is cancelled and the text from line 0 is used as the password.</li>
 *   <li>The response is forwarded to the Velocity proxy via the plugin-message channel.</li>
 * </ol>
 *
 * <p><strong>Note:</strong> To upgrade to the MC 1.21.5 vanilla dialog screen once the
 * Paper/PacketEvents API is stable, replace this class with a {@code VanillaDialogHandler}
 * that sends a {@code ClientboundCustomDialogPacket} and listens for the corresponding
 * client response packet. The {@link DialogHandler} interface ensures the rest of the
 * code requires no changes.
 */
public class SignEditorDialogHandler extends PacketListenerAbstract implements DialogHandler {

    private final BetterLoginBridge plugin;
    private final Set<UUID> pendingAuth;

    /** Tracks which players are waiting for sign input and whether they are registering. */
    private final Map<UUID, Boolean> awaitingSign = new ConcurrentHashMap<>();

    private static final String SEP = "\0";

    public SignEditorDialogHandler(BetterLoginBridge plugin, Set<UUID> pendingAuth) {
        this.plugin = plugin;
        this.pendingAuth = pendingAuth;
        // Register the packet listener
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    @Override
    public void showLoginDialog(Player player) {
        sendSignEditor(player, false);
        promptTitle(player, false);
    }

    @Override
    public void showRegisterDialog(Player player) {
        sendSignEditor(player, true);
        promptTitle(player, true);
    }

    @Override
    public void handleResponse(Player player, String input, boolean isRegister) {
        // Trim whitespace to avoid accidental leading/trailing spaces in the password
        String password = input.trim();
        forwardToVelocity(player, password, isRegister);
    }

    // ------------------------------------------------------------------
    // PacketEvents packet listener
    // ------------------------------------------------------------------

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;

        Player player = (Player) event.getPlayer();
        if (player == null) return;

        UUID uuid = player.getUniqueId();
        Boolean isRegister = awaitingSign.remove(uuid);
        if (isRegister == null) return; // not our sign

        // Cancel packet so the server never processes it as a real sign update
        event.setCancelled(true);

        WrapperPlayClientUpdateSign wrapper = new WrapperPlayClientUpdateSign(event);
        String[] lines = wrapper.getTextLines();
        String input = (lines != null && lines.length > 0 && lines[0] != null) ? lines[0] : "";

        // Hand off via the main-thread safe scheduler
        plugin.getServer().getScheduler().runTask(plugin,
            () -> handleResponse(player, input, isRegister));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void sendSignEditor(Player player, boolean isRegister) {
        // Position: directly below the player's feet (hidden underground if y allows)
        Location loc = player.getLocation();
        int x = loc.getBlockX();
        int y = Math.max(loc.getBlockY() - 5, -64);
        int z = loc.getBlockZ();

        Vector3i signPos = new Vector3i(x, y, z);

        // Send a fake oak sign block so the client agrees there is a sign at this location
        BlockData signData = plugin.getServer().createBlockData(Material.OAK_SIGN);
        WrapperPlayServerBlockChange blockChange = new WrapperPlayServerBlockChange(
            signPos,
            SpigotConversionUtil.fromBukkitBlockData(signData).getGlobalId()
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, blockChange);

        // Open the sign editor
        WrapperPlayServerOpenSignEditor openSign = new WrapperPlayServerOpenSignEditor(
            signPos, true);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, openSign);

        awaitingSign.put(player.getUniqueId(), isRegister);
    }

    /** Show an action bar and title so the player knows what to do. */
    private void promptTitle(Player player, boolean isRegister) {
        String cfg = isRegister
            ? plugin.getConfig().getString("messages.register-prompt", "&eType your new password on line 1 of the sign, then click Done.")
            : plugin.getConfig().getString("messages.login-prompt", "&eType your password on line 1 of the sign, then click Done.");
        player.sendActionBar(legacyToComponent(cfg));
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

    private static Component legacyToComponent(String legacy) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
            .legacyAmpersand().deserialize(legacy);
    }
}
