package com.betterlogin.paper.dialog;

import org.bukkit.entity.Player;

/**
 * Abstraction over the dialog/input mechanism used to collect a password from the player.
 *
 * <p>The default implementation uses the vanilla sign-editor GUI (see {@link SignEditorDialogHandler}).
 * Once the Paper API stabilises support for the MC 1.21.5 custom dialog screen, a
 * {@code VanillaDialogHandler} can be swapped in without touching any other code.
 */
public interface DialogHandler {

    /**
     * Show the login dialog to a player who already has an account.
     *
     * @param player the player to prompt
     */
    void showLoginDialog(Player player);

    /**
     * Show the registration dialog to a player who is creating an account for the first time.
     *
     * @param player the player to prompt
     */
    void showRegisterDialog(Player player);

    /**
     * Called by the listener layer when the player submits a response.
     * The implementation must forward the result to the Velocity proxy.
     *
     * @param player     the player who submitted
     * @param input      the raw text they entered
     * @param isRegister true if this is a first-join registration, false if login
     */
    void handleResponse(Player player, String input, boolean isRegister);
}
