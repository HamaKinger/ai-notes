package com.ainotes.ai;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 假的 {@link ChatModel}:对话(流式)与文本工具(流式 + 一次性)共用。
 *
 * <p>它替掉的是"真正的 HTTP 调用 + 真模型",留下的正是本项目要自己保证的东西:
 * 事件顺序、增量拼接、用量统计、失败时的降级。每帧都带上用量 —— 硅基流动的分块就是这样,
 * 收尾只取最后一块(真实世界的行为一并复刻,免得把"每帧都带 usage"当成理所当然)。
 *
 * <p>四处刻意的不一样:{@code streaming} 只吐流、{@code answering} 只走一次性调用 ——
 * 谁被调到、谁不被调到,测试里直接看得出来。
 */
final class TestChatModel implements ChatModel {

    /** 固定用量,断言时不必再算 */
    private static final Usage USAGE = new DefaultUsage(11, 7);

    private final List<String> deltas;
    private final String answer;
    private final RuntimeException failure;
    private final AtomicReference<Prompt> lastPrompt = new AtomicReference<>();

    private TestChatModel(List<String> deltas, String answer, RuntimeException failure) {
        this.deltas = deltas;
        this.answer = answer;
        this.failure = failure;
    }

    /** 流式:按给定增量逐块吐,看起来像正常回答。 */
    static TestChatModel streaming(String... deltas) {
        return new TestChatModel(List.of(deltas), null, null);
    }

    /** 流式:吐完这些增量后抛错 —— 用来测"半截结果照样给用户看"。 */
    static TestChatModel streamingThenFailing(String failureMessage, String... deltas) {
        return new TestChatModel(List.of(deltas), null, new IllegalStateException(failureMessage));
    }

    /** 一次性调用:直接返回整段(摘要 / 标签走这条)。 */
    static TestChatModel answering(String answer) {
        return new TestChatModel(List.of(), answer, null);
    }

    /** 一次性调用:抛错。 */
    static TestChatModel failing(String failureMessage) {
        return new TestChatModel(List.of(), null, new IllegalStateException(failureMessage));
    }

    Prompt lastPrompt() {
        return lastPrompt.get();
    }

    /** 最后一次调用发给模型的 system 提示词 */
    String lastSystem() {
        return lastPrompt().getInstructions().getFirst().getText();
    }

    /** 最后一次调用发给模型的最后一条消息(通常是这一次要处理的内容) */
    String lastUser() {
        return lastPrompt().getInstructions().getLast().getText();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        lastPrompt.set(prompt);
        if (failure != null) {
            throw failure;
        }
        return response(answer == null ? "" : answer);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        lastPrompt.set(prompt);
        Flux<ChatResponse> chunks = Flux.fromIterable(deltas).map(TestChatModel::response);
        return failure == null ? chunks : chunks.concatWith(Flux.error(failure));
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().model("test-model").usage(USAGE).build());
    }
}
