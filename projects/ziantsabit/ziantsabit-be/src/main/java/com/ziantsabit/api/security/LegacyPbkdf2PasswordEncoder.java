package com.ziantsabit.api.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Verifies a password hash written before this API used Spring Security's own
 * encodings: {@code pbkdf2_sha256$<iterations>$<salt>$<base64 hash>}, stored
 * as {@code {pbkdf2-legacy}...} since V4.
 *
 * <p>It only ever matches. {@link UserAccounts} is a
 * {@code UserDetailsPasswordService}, so the first successful login with such
 * a hash stores the password again as bcrypt, and nothing is ever written in
 * this format. Delete this class once {@code SELECT count(*) FROM app_user
 * WHERE password LIKE '{pbkdf2-legacy}%'} is zero everywhere.
 */
public final class LegacyPbkdf2PasswordEncoder implements PasswordEncoder {

    public static final String ID = "pbkdf2-legacy";

    @Override
    public String encode(CharSequence rawPassword) {
        throw new UnsupportedOperationException("Legacy hashes are only verified, never written");
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || rawPassword.isEmpty() || encodedPassword == null) {
            return false;
        }
        String[] parts = encodedPassword.split("\\$", 4);
        if (parts.length != 4 || !parts[0].equals("pbkdf2_sha256")) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            PBEKeySpec spec = new PBEKeySpec(rawPassword.toString().toCharArray(),
                    parts[2].getBytes(StandardCharsets.UTF_8), iterations, expected.length * 8);
            byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(actual, expected);
        } catch (IllegalArgumentException | GeneralSecurityException corrupt) {
            return false;
        }
    }
}
