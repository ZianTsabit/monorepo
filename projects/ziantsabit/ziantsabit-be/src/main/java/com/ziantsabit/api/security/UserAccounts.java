package com.ziantsabit.api.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.ziantsabit.api.repository.UserRepository;

/**
 * The accounts allowed to write. Any enabled account is the owner: the site
 * has no roles and no visitor accounts.
 *
 * <p>Also a {@link UserDetailsPasswordService}: when a login succeeds against
 * a hash the {@link PasswordEncoder} would no longer write (a
 * {@code {pbkdf2-legacy}} one, or bcrypt at an older cost), Spring's
 * {@code DaoAuthenticationProvider} hands the re-encoded password here.
 */
@Component
public class UserAccounts implements UserDetailsService, UserDetailsPasswordService {

    private final UserRepository users;
    private final PasswordEncoder passwords;

    public UserAccounts(UserRepository users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return users.findByUsername(username)
                .map(account -> User.withUsername(account.username())
                        .password(account.passwordHash())
                        .disabled(!account.enabled())
                        .authorities("ROLE_OWNER")
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException(username));
    }

    @Override
    public UserDetails updatePassword(UserDetails user, String newPassword) {
        users.updatePassword(user.getUsername(), newPassword);
        return User.withUserDetails(user).password(newPassword).build();
    }

    public boolean exists(String username) {
        return users.exists(username);
    }

    public void create(String username, String password) {
        users.insert(username, passwords.encode(password));
    }

    public void recordLogin(String username) {
        users.touchLastLogin(username);
    }
}
