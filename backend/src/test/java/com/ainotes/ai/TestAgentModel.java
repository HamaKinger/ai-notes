package com.ainotes.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/**
 * 假的"会调工具的模型":按剧本一轮一轮地应答。
 *
 * <p>它要复刻的正是 Agent 循环最容易出错的地方 —— **工具调用不是独立一轮,而是和正文一起从流里出来**:
 * 真实服务商把工具调用的参数逐字吐进 delta,最后一块才给出完整的 tool_calls。所以这里每一轮都是
 * "先若干块正文,最后一块带 tool_calls"(没有工具调用的那一轮就只有正文)。
 *
 * <p>每次 {@code stream} 消耗一轮剧本,并把这次拿到的 {@link Prompt} 记下来 ——
 * 断言"模型第二轮到底看见了什么"(工具结果有没有回灌、助手消息有没有带上 tool_calls)全靠它。
 * 剧本用完还来调用就直接报错:那说明循环没有按预期收尾。
 */
final class TestAgentModel implements ChatModel {

    private static final Usage USAGE = new DefaultUsage(11, 7);

    private final Deque<Turn> script = new ArrayDeque<>();
    private final List<Prompt> prompts = new ArrayList<>();
    private int callSeq;
    /** 下一次订阅直接失败(还没吐出任何分块)—— 复刻服务商的 "stream was reset" */
    private boolean failNext;

    private record Turn(List<AssistantMessage.ToolCall> calls, List<String> deltas) {
    }

    static TestAgentModel scripted() {
        return new TestAgentModel();
    }

    /** 下一轮:先吐 {@code narration} 若干块,最后一块给出工具调用。 */
    TestAgentModel toolCall(String name, String arguments, String... narration) {
        callSeq++;
        script.add(new Turn(
                List.of(new AssistantMessage.ToolCall("call_" + callSeq, "function", name, arguments)),
                List.of(narration)));
        return this;
    }

    /** 下一轮:只说话,不调工具 —— Agent 会在这里收尾。 */
    TestAgentModel says(String... deltas) {
        script.add(new Turn(List.of(), List.of(deltas)));
        return this;
    }

    /**
     * 下一次 {@link #stream} 在**吐任何分块之前**就报错。
     *
     * <p>实测服务商偶尔这样(okhttp: `stream was reset: CANCEL`):这一轮等于白跑,
     * Agent 应当安全地重来一次 —— 剧本不动,所以重试能拿到真正的下一轮。
     */
    TestAgentModel failsOnce() {
        this.failNext = true;
        return this;
    }

    /** 每一轮模型收到的提示词(按调用顺序)。 */
    List<Prompt> prompts() {
        return prompts;
    }

    Prompt lastPrompt() {
        return prompts.get(prompts.size() - 1);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        throw new UnsupportedOperationException("Agent 只走流式:call() 不该被调到");
    }

    /**
     * 默认选项必须是个 {@code ToolCallingChatOptions}(真实世界里 {@code OpenAiChatModel} 给的就是
     * {@code OpenAiChatOptions})。
     *
     * <p>这不是凑测试:ChatClient 把工具定义塞进请求时,走的是
     * {@code chatModel.getOptions().mutate()} 建出来的 builder,并且**只在它是
     * {@code ToolCallingChatOptions.Builder} 时才把 toolCallbacks 放进去**
     * (DefaultChatClientUtils.toChatClientRequest)。给个普通 ChatOptions,工具就静默地
     * 到不了模型手里 —— 实测就是"模型永远不调用工具"。所以这里如实复刻真实模型的形状。
     */
    @Override
    public ChatOptions getOptions() {
        return OpenAiChatOptions.builder().model("test-model").build();
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        prompts.add(prompt);
        if (failNext) {
            failNext = false;
            return Flux.error(new IllegalStateException("stream was reset: CANCEL"));
        }
        Turn turn = script.poll();
        if (turn == null) {
            return Flux.error(new IllegalStateException("剧本用完了:模型被多叫了一轮"));
        }
        Flux<ChatResponse> text = Flux.fromIterable(turn.deltas()).map(TestAgentModel::textChunk);
        if (turn.calls().isEmpty()) {
            return text;
        }
        return text.concatWith(Flux.just(toolCallsChunk(turn.calls())));
    }

    private static ChatResponse textChunk(String text) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().model("test-model").usage(USAGE).build());
    }

    /** 工具调用块:没有正文,只有 tool_calls —— 真实流式的最后一块就是这样。 */
    private static ChatResponse toolCallsChunk(List<AssistantMessage.ToolCall> calls) {
        return new ChatResponse(
                List.of(new Generation(AssistantMessage.builder().toolCalls(calls).build())),
                ChatResponseMetadata.builder().model("test-model").usage(USAGE).build());
    }
}
