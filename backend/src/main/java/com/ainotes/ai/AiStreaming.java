package com.ainotes.ai;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * 流式调用里"从 {@link ChatResponse} 抠数据"的那几件小事。
 *
 * <p>对话({@link ChatService})和文本工具({@link AiTextService})都要做同样三件事:
 * 取增量、记用量、把异常翻译成给用户看的一句话。抽到一处是为了两边**别慢慢长歪** ——
 * 尤其是错误文案:同一个模型 500,从两个入口说出来的话不一样时,排查的人会以为是两个问题。
 *
 * <p>刻意不是 public:它只服务于 {@code com.ainotes.ai} 这一层,没有对外语义。
 */
final class AiStreaming {

    /** 给用户的错误文字上限,避免把整段 HTML/JSON 甩到界面上 */
    static final int ERROR_MESSAGE_MAX = 300;

    private AiStreaming() {
    }

    /** 这一块里的正文增量(可能为空:只有 role 或只有 usage 的块)。 */
    static String deltaOf(ChatResponse response) {
        Generation generation = response == null ? null : response.getResult();
        AssistantMessage output = generation == null ? null : generation.getOutput();
        return output == null ? null : output.getText();
    }

    /**
     * 这一块里的工具调用(可能为空)。
     *
     * <p>流式下工具参数是**逐字**流出来的,同一个调用会跨好几个块出现;这里的取法只是"把块里的
     * 交出来",合并是调用方的事(见 {@code AgentService.turns}:按 id 合并,留参数最全的那份)。
     * 之所以不在这儿合并:合并要跨块保存状态,那就不是"抠一下数据"了。
     */
    static List<AssistantMessage.ToolCall> toolCallsOf(ChatResponse response) {
        Generation generation = response == null ? null : response.getResult();
        AssistantMessage output = generation == null ? null : generation.getOutput();
        List<AssistantMessage.ToolCall> calls = output == null ? null : output.getToolCalls();
        return calls == null ? List.of() : calls;
    }

    static Usage usageOf(ChatResponse response) {
        return response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
    }

    static Integer tokensOf(Usage usage, boolean prompt) {
        if (usage == null) {
            return null;
        }
        Integer tokens = prompt ? usage.getPromptTokens() : usage.getCompletionTokens();
        return tokens == null || tokens <= 0 ? null : tokens;
    }

    /** 给前端的错误码:只区分"值得用户换个时间再来"和"其它"。 */
    static String codeOf(Throwable error) {
        String name = rootCause(error).getClass().getSimpleName();
        return name.contains("Timeout") ? "MODEL_TIMEOUT" : "MODEL_ERROR";
    }

    static String rootMessage(Throwable error) {
        Throwable root = rootCause(error);
        String detail = root.getMessage() == null || root.getMessage().isBlank()
                ? root.getClass().getSimpleName()
                : root.getMessage();
        return detail.length() <= ERROR_MESSAGE_MAX ? detail : detail.substring(0, ERROR_MESSAGE_MAX) + "…";
    }

    private static Throwable rootCause(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }
}
