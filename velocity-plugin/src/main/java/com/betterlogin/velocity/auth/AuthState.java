package com.betterlogin.velocity.auth;

/** Represents the current authentication stage of a connected player. */
public enum AuthState {
    /** Player has not yet been evaluated (initial state). */
    UNKNOWN,
    /** Premium player – Mojang-verified, auto-authenticated. */
    PREMIUM,
    /** Offline player who has an active, unexpired session. */
    SESSION_VALID,
    /** Offline player waiting for the Paper server to show the auth dialog. */
    PENDING_DIALOG,
    /** Offline player who has submitted a registration attempt that Velocity is validating. */
    PENDING_REGISTER,
    /** Offline player who has submitted a login attempt that Velocity is validating. */
    PENDING_LOGIN,
    /** Player has successfully authenticated and is free to play. */
    AUTHENTICATED,
    /** Player has exceeded the maximum number of failed attempts and should be kicked. */
    KICKED
}
