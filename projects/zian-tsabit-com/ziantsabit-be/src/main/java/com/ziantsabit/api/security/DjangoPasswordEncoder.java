package com.ziantsabit.api.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Django's password hash format, read and written:
 * {@code pbkdf2_sha256$<iterations>$<salt>$<base64 hash>}.
 *
 * <p>The point is continuity. {@code auth_user} already holds the owner's
 * password, hashed by Django; reading that format means the same password logs
 * in after the switch, and writing it means the Django image could be rolled
 * back onto the same database without anyone's login breaking.
 *
 * <p>The iteration count is read from each stored hash, so a hash Django wrote
 * with a different count still verifies. A hash starting {@code !} is Django's
 * "unusable password" and matches nothing.
 */
public class DjangoPasswordEncoder implements PasswordEncoder {

    private static final String ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final int iterations;

    public DjangoPasswordEncoder(int iterations) {
        this.iterations = iterations;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        StringBuilder salt = new StringBuilder();
        for (int index = 0; index < 22; index++) {
            salt.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return "pbkdf2_sha256$" + iterations + "$" + salt + "$"
                + pbkdf2("PBKDF2WithHmacSHA256", rawPassword.toString(), salt.toString(), iterations, 32);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return false;
        }
        String[] parts = encodedPassword.split("\\$", 4);
        if (parts.length != 4) {
            return false;
        }
        String algorithm = switch (parts[0]) {
            case "pbkdf2_sha256" -> "PBKDF2WithHmacSHA256";
            case "pbkdf2_sha1" -> "PBKDF2WithHmacSHA1";
            default -> null;
        };
        if (algorithm == null) {
            return false;
        }
        int rounds;
        try {
            rounds = Integer.parseInt(parts[1]);
        } catch (NumberFormatException corrupt) {
            return false;
        }
        int length = Base64.getDecoder().decode(parts[3]).length;
        String computed = pbkdf2(algorithm, rawPassword.toString(), parts[2], rounds, length);
        return computed != null && MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.US_ASCII), parts[3].getBytes(StandardCharsets.US_ASCII));
    }

    private static String pbkdf2(String algorithm, String password, String salt, int rounds, int bytes) {
        if (password.isEmpty()) {
            // The JDK refuses an empty password outright; no stored hash is of one.
            return null;
        }
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt.getBytes(StandardCharsets.UTF_8),
                    rounds, bytes * 8);
            byte[] hash = SecretKeyFactory.getInstance(algorithm).generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (GeneralSecurityException failed) {
            return null;
        }
    }
}
