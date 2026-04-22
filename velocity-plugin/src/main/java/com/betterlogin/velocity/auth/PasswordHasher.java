package com.betterlogin.velocity.auth;

import org.mindrot.jbcrypt.BCrypt;

/** Thread-safe BCrypt password hashing utility. */
public final class PasswordHasher {

    private static final int BCRYPT_ROUNDS = 12;

    private PasswordHasher() {}

    public static String hash(String plaintext) {
        return BCrypt.hashpw(plaintext, BCrypt.gensalt(BCRYPT_ROUNDS));
    }

    public static boolean verify(String plaintext, String hashed) {
        try {
            return BCrypt.checkpw(plaintext, hashed);
        } catch (Exception e) {
            return false;
        }
    }
}
