package com.ainotes.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 读取当前登录用户。
 *
 * <p>若未携带 Token 且开启了开发放行模式,回落到演示账号,方便本地直接调用接口。
 */
@Component
public class CurrentUser {

    private final AppUserRepository users;
    private final boolean devPermitAll;
    private final String demoEmail;

    public CurrentUser(AppUserRepository users,
                       @Value("${ai-notes.security.dev-permit-all:true}") boolean devPermitAll,
                       @Value("${ai-notes.security.demo-user-email:demo@ai-notes.local}") String demoEmail) {
        this.users = users;
        this.devPermitAll = devPermitAll;
        this.demoEmail = demoEmail;
    }

    public Optional<AppUser> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        try {
            return users.findById(UUID.fromString(authentication.getName()));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    public Optional<AppUser> findWithDevFallback() {
        Optional<AppUser> current = find();
        if (current.isPresent() || !devPermitAll) {
            return current;
        }
        return users.findByEmailIgnoreCase(demoEmail);
    }
}
