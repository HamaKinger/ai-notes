package com.ainotes.auth;

import com.ainotes.workspace.Workspace;
import com.ainotes.workspace.WorkspaceMember;
import com.ainotes.workspace.WorkspaceMemberRepository;
import com.ainotes.workspace.WorkspaceRepository;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final AppUserRepository users;
    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(AppUserRepository users,
                       WorkspaceRepository workspaces,
                       WorkspaceMemberRepository members,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.users = users;
        this.workspaces = workspaces;
        this.members = members;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResult register(String email, String rawPassword, String displayName) {
        String normalized = normalize(email);
        if (users.existsByEmailIgnoreCase(normalized)) {
            throw new IllegalArgumentException("该邮箱已注册");
        }
        String name = (displayName == null || displayName.isBlank()) ? normalized : displayName.trim();
        AppUser user = users.save(new AppUser(normalized, name, passwordEncoder.encode(rawPassword)));

        // 新用户默认获得一个工作区
        Workspace workspace = workspaces.save(new Workspace(name + " 的知识库"));
        members.save(new WorkspaceMember(workspace.getId(), user.getId(), "owner"));

        return new AuthResult(jwtService.issue(user), user);
    }

    @Transactional(readOnly = true)
    public AuthResult login(String email, String rawPassword) {
        AppUser user = users.findByEmailIgnoreCase(normalize(email))
                .orElseThrow(() -> new IllegalArgumentException("邮箱或密码不正确"));
        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("邮箱或密码不正确");
        }
        return new AuthResult(jwtService.issue(user), user);
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public record AuthResult(String token, AppUser user) {
    }
}
