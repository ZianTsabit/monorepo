package com.ziantsabit.api.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.ziantsabit.api.repository.UserRepository;

/**
 * The accounts allowed to write, read straight from Django's {@code auth_user}.
 *
 * <p>Any active account is the owner, as {@code IsAuthenticated} meant in DRF:
 * there are no roles on this site, and no visitor accounts at all.
 */
@Component
public class UserAccounts implements UserDetailsService {

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
                        .disabled(!account.active())
                        .authorities("ROLE_OWNER")
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException(username));
    }

    public boolean exists(String username) {
        return users.exists(username);
    }

    /** A new active account, its password hashed in Django's format. */
    public void create(String username, String password) {
        users.insert(username, passwords.encode(password));
    }

    public void recordLogin(String username) {
        users.touchLastLogin(username);
    }
}
