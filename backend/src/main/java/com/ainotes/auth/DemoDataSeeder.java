package com.ainotes.auth;

import com.ainotes.workspace.Workspace;
import com.ainotes.workspace.WorkspaceMember;
import com.ainotes.workspace.WorkspaceMemberRepository;
import com.ainotes.workspace.WorkspaceRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 演示数据:首次启动创建一个演示账号,并把没有归属的既有工作区挂到它名下。
 *
 * <p>默认账号:demo@ai-notes.local / demo1234
 */
@Component
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final AppUserRepository users;
    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final PasswordEncoder passwordEncoder;
    private final String demoEmail;

    public DemoDataSeeder(AppUserRepository users,
                          WorkspaceRepository workspaces,
                          WorkspaceMemberRepository members,
                          PasswordEncoder passwordEncoder,
                          @Value("${ai-notes.security.demo-user-email:demo@ai-notes.local}") String demoEmail) {
        this.users = users;
        this.workspaces = workspaces;
        this.members = members;
        this.passwordEncoder = passwordEncoder;
        this.demoEmail = demoEmail;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppUser demo = users.findByEmailIgnoreCase(demoEmail).orElseGet(() -> {
            AppUser created = users.save(
                    new AppUser(demoEmail, "演示用户", passwordEncoder.encode("demo1234")));
            log.info("已创建演示账号:{} / demo1234", demoEmail);
            return created;
        });

        List<Workspace> orphanWorkspaces = workspaces.findAll().stream()
                .filter(ws -> members.countByIdWorkspaceId(ws.getId()) == 0)
                .toList();
        for (Workspace workspace : orphanWorkspaces) {
            members.save(new WorkspaceMember(workspace.getId(), demo.getId(), "owner"));
            log.info("已把工作区 '{}' 归属到演示账号", workspace.getName());
        }

        if (workspaces.findAll().isEmpty()) {
            Workspace workspace = workspaces.save(new Workspace("我的知识库"));
            members.save(new WorkspaceMember(workspace.getId(), demo.getId(), "owner"));
            log.info("已为演示账号创建工作区 '{}'", workspace.getName());
        }
    }
}
