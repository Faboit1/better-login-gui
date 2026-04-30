package com.betterlogin.paper.command;

import com.betterlogin.paper.BetterLoginBridge;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Handles {@code /login <password>} and {@code /register <password>} for players
 * whose Minecraft client does not support native 1.21.6+ dialog screens.
 *
 * <p>These players are added to {@code pendingAuth} by
 * {@link com.betterlogin.paper.dialog.VanillaDialogHandler#sendFallbackMessage} with
 * movement and interaction restrictions identical to dialog players.  When they submit
 * their password via this command, the attempt is forwarded to the Velocity proxy for
 * validation just as a dialog response would be.</p>
 */
public class FallbackAuthCommand implements CommandExecutor {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final BetterLoginBridge plugin;
    /** {@code true} if this instance handles {@code /register}, {@code false} for {@code /login}. */
    private final boolean isRegisterCommand;

    public FallbackAuthCommand(BetterLoginBridge plugin, boolean isRegisterCommand) {
        this.plugin = plugin;
        this.isRegisterCommand = isRegisterCommand;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        UUID uuid = player.getUniqueId();

        // Only process if Velocity sent AUTH_REQUIRED for this player
        if (!plugin.getPendingAuth().contains(uuid)) {
            return true;
        }

        // Only act if the player is in fallback mode (pendingRegistration is set)
        Boolean pendingIsRegister = plugin.getPendingRegistration().get(uuid);
        if (pendingIsRegister == null) {
            // Player is using the native dialog flow, not this fallback command
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(LEGACY.deserialize("&cUsage: /" + label + " <password>"));
            return true;
        }

        // Validate the player is using the correct command for their auth flow
        if (isRegisterCommand && !pendingIsRegister) {
            player.sendMessage(LEGACY.deserialize("&cYou already have an account. Please use /login <password> instead."));
            return true;
        }
        if (!isRegisterCommand && pendingIsRegister) {
            player.sendMessage(LEGACY.deserialize("&cYou don't have an account yet. Please use /register <password> instead."));
            return true;
        }

        // Forward the password to Velocity via the dialog handler
        String password = args[0];
        plugin.getDialogHandler().handleResponse(player, password, isRegisterCommand);
        return true;
    }
}
