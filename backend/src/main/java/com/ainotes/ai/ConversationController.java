package com.ainotes.ai;

import com.ainotes.ai.dto.ChatMessageView;
import com.ainotes.ai.dto.ConversationView;
import com.ainotes.auth.AppUser;
import com.ainotes.auth.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话的增删改查(流式问答在 {@link ChatController})。
 *
 * <p>会话本身不带"谁是主人"的过滤条件:这个后端至今是按工作区划分权限的单租户形态
 * (笔记接口也一样)。{@code user_id} 只是记下是谁建的,将来要分权时它就是依据。
 */
@RestController
@RequestMapping("/api/v1")
public class ConversationController {

    private final ChatService chatService;
    private final CurrentUser currentUser;

    public ConversationController(ChatService chatService, CurrentUser currentUser) {
        this.chatService = chatService;
        this.currentUser = currentUser;
    }

    /** 侧栏会话列表:最近说过话的排在前面。 */
    @GetMapping("/workspaces/{workspaceId}/conversations")
    public List<ConversationView> list(@PathVariable UUID workspaceId) {
        return chatService.list(workspaceId);
    }

    /** 新建会话。标题可以不传 —— 第一句提问会自动成为标题(见 Conversation#titleFromFirstQuestion)。 */
    @PostMapping("/workspaces/{workspaceId}/conversations")
    public ConversationView create(
            @PathVariable UUID workspaceId,
            @RequestBody(required = false) ConversationRequest request) {
        UUID userId = currentUser.findWithDevFallback().map(AppUser::getId).orElse(null);
        return chatService.create(workspaceId, userId, request == null ? null : request.title());
    }

    /** 会话的完整历史(含每条回答的引用),前端刷新或切回会话时用它重建气泡。 */
    @GetMapping("/conversations/{id}/messages")
    public List<ChatMessageView> messages(@PathVariable UUID id) {
        return chatService.messages(id);
    }

    @PatchMapping("/conversations/{id}")
    public ConversationView rename(@PathVariable UUID id, @RequestBody ConversationRequest request) {
        return chatService.rename(id, request.title());
    }

    @DeleteMapping("/conversations/{id}")
    public void delete(@PathVariable UUID id) {
        chatService.delete(id);
    }

    /** 新建/重命名共用;两个动作都只需要标题。 */
    public record ConversationRequest(String title) {
    }
}
