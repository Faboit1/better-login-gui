package com.betterlogin.velocity.auth;

/** Represents the current authentication stage of a connected player. */
public enum AuthState {
    /** Player has not yet been evaluated (initial state). */
    UNKNOWN,
    /** Premium player – Mojang-verified, auto-authenticated. */
    PREMIUM,
    /** Offline player waiting for the Paper server to show the auth dialog. */
    PENDING_DIALOG,
    /** Player has successfully authenticated via AuthMe and is free to play. */
    AUTHENTICATED
}
