package com.ainotes.ai;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 文本工具(设计文档 §2.3 的后三项):选中文本的润色/翻译/续写/总结/解释,以及给一篇笔记
 * 生成摘要、推荐标签。
 *
 * <p><b>不落库、不改笔记。</b>这个服务只负责"把一段文字变成另一段文字":要不要替换选中、
 * 要不要存成摘要,由用户在前端点一下再走原本的保存链路(带版本、带索引重建)。所以这里既没有
 * 事务,也没有 {@code @Transactional} —— 它不碰数据库。
 *
 * <p>两种调用方式并存是有意的:选中文本操作**流式**(结果长,用户盯着那一段等),
 * 摘要/标签**一次性返回**(很短,而且前端要拿整段去填摘要/铺标签,半截没有意义)。
 *
 * <p>选中文本操作在**超长时直接报错**,而不是像摘要/标签那样截断:截断后返回的"润色结果"
 * 只覆盖前半段,用户一点替换就把后半段删了 —— 宁可让他分两段处理。
 */
@Service
public class AiTextService {

    private static final Logger log = LoggerFactory.getLogger(AiTextService.class);

    /** 推荐标签的条数上限,与提示词里说的 3~5 个对齐(模型偶尔会多给) */
    static final int MAX_TAGS = 5;

    /** 单个标签的字数上限:超过它基本是模型把整句话当标签了 */
    static final int MAX_TAG_CHARS = 12;

    private final ChatClient chatClient;
    private final String chatModel;
    private final int maxInputChars;

    public AiTextService(
            ChatClient chatClient,
            @Value("${spring.ai.openai.chat.options.model:unknown}") String chatModel,
            @Value("${ai-notes.ai.max-input-chars:6000}") int maxInputChars) {
        this.chatClient = chatClient;
        this.chatModel = chatModel;
        // 配错(0 / 负数)时退回默认值,而不是让整段处理直接不可用
        this.maxInputChars = maxInputChars > 0 ? maxInputChars : 6000;
    }

    // ---- 选中文本操作(SSE) ------------------------------------------

    /**
     * 流式处理选中的一段文字。
     *
     * <p>事件顺序与对话一致:{@code meta} → {@code token}* → {@code done}(失败时以
     * {@code error} 收尾,已经生成的部分照常发过)。收尾那个 Mono 不做
     * {@code subscribeOn(boundedElastic)}:它只是拼一个事件对象,没有阻塞调用 ——
     * 对话那边要落库才需要换线程池。
     */
    public Flux<TextEvent> inline(String text, TextAction action, String target) {
        String material = require(text, "选中的文字不能为空");
        if (material.length() > maxInputChars) {
            throw new IllegalArgumentException(
                    "选中的文字太长了(" + material.length() + " 字,上限 " + maxInputChars + " 字),请分段处理");
        }

        StringBuilder answer = new StringBuilder();
        AtomicReference<Usage> lastUsage = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Flux<TextEvent> head = Flux.just(TextEvent.meta(action, chatModel));

        Flux<TextEvent> body = chatClient.prompt()
                .system(AiPrompts.textSystem(action, targetOr(material, target)))
                .user(AiPrompts.textInput(material))
                .stream()
                .chatResponse()
                .<TextEvent>handle((response, sink) -> {
                    Usage usage = AiStreaming.usageOf(response);
                    if (usage != null) {
                        lastUsage.set(usage);
                    }
                    String delta = AiStreaming.deltaOf(response);
                    if (delta == null || delta.isEmpty()) {
                        return;
                    }
                    answer.append(delta);
                    sink.next(TextEvent.token(delta));
                })
                .doOnError(failure::set)
                .onErrorResume(error -> Flux.empty());

        Mono<TextEvent> tail = Mono.fromCallable(() -> {
            Throwable error = failure.get();
            if (error == null) {
                return TextEvent.done(
                        AiStreaming.tokensOf(lastUsage.get(), true),
                        AiStreaming.tokensOf(lastUsage.get(), false),
                        answer.length());
            }
            log.warn("文本工具 {} 生成失败,已生成 {} 字: {}", action, answer.length(), error.toString());
            return TextEvent.error(AiStreaming.codeOf(error), "模型调用失败:" + AiStreaming.rootMessage(error));
        });

        return Flux.concat(head, body, tail);
    }

    // ---- 摘要 / 标签(一次性) ----------------------------------------

    /** 给一篇笔记生成一句话摘要(要不要保存由用户决定,见前端「保存为摘要」)。 */
    public String summarize(String title, String content) {
        String material = clip(require(content, "笔记还是空的,先写点内容再生成摘要"));
        String answer = chatClient.prompt()
                .system(AiPrompts.summarySystem())
                .user(AiPrompts.summaryInput(title, material))
                .call()
                .content();
        return oneLine(require(answer, "模型没有返回摘要"));
    }

    /**
     * 推荐标签。{@code existing} 是工作区里已有的标签 —— 传给模型是为了**尽量复用**,
     * 而不是过滤掉(笔记里已经在用的标签才过滤,见 {@link #parseTags})。
     */
    public List<String> suggestTags(String title, String content, List<String> existing) {
        String material = clip(require(content, "笔记还是空的,先写点内容再推荐标签"));
        String answer = chatClient.prompt()
                .system(AiPrompts.tagsSystem())
                .user(AiPrompts.tagsInput(title, material, existing))
                .call()
                .content();
        return parseTags(require(answer, "模型没有返回标签"), material);
    }

    /**
     * 把模型的输出解析成标签列表。
     *
     * <p>没让它吐 JSON:8B 模型在 JSON 上偶发的引号/逗号错误会让整份结果作废,而"每行一个"
     * 几乎不会错。解析再放宽一点 —— `#`、编号、项目符号、引号、逗号分隔都顺手清掉,
     * 这样提示词偶尔没被遵守也不会直接变成空列表。
     *
     * <p>过滤三件事:太长的(整句话)、带空格的(不是标签)、**笔记里已经用过的**
     * (再推荐一次只会让用户困惑)。
     */
    static List<String> parseTags(String raw, String content) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        for (String piece : raw.split("[\\n,、;]+")) {
            String tag = piece.strip()
                    .replaceAll("^[#*\\-•\\d.、)）\\s]+", "")
                    .replaceAll("[\"'`「」『』【】\\s]+$", "")
                    .replaceAll("^[\"'`「」『』【】]+", "")
                    .strip();
            if (tag.isEmpty() || tag.length() > MAX_TAG_CHARS) {
                continue;
            }
            if (tag.chars().anyMatch(Character::isWhitespace)) {
                continue;
            }
            if (content != null && content.contains("#" + tag)) {
                continue;
            }
            if (tags.stream().anyMatch(existing -> existing.equalsIgnoreCase(tag))) {
                continue;
            }
            tags.add(tag);
            if (tags.size() >= MAX_TAGS) {
                break;
            }
        }
        return List.copyOf(tags);
    }

    // ---- 内部 --------------------------------------------------------

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    /**
     * 摘要是往窄位置(双链预览、检索结果)塞的一句话:模型偶尔会带上引号、换行或
     * Markdown 强调号,这里统一压成一行。
     */
    private static String oneLine(String answer) {
        return answer.strip()
                .replaceAll("^[\"'`「『]+|[\"'`」』]+$", "")
                .replaceAll("\\s*\\n+\\s*", " ")
                .strip();
    }

    /** 整篇处理时的截断:上限之外的内容丢掉,并在末尾写清楚,免得模型以为笔记就到这儿。 */
    private String clip(String content) {
        if (content.length() <= maxInputChars) {
            return content;
        }
        return content.substring(0, maxInputChars) + "\n…(正文过长,已截断)";
    }

    /**
     * 翻译的目标语言。没说就"反着来":中文原文译成英文,其它译成中文 ——
     * 中英互译是最常见的一档,不选也比默认译成同一种语言强。
     */
    private static String targetOr(String text, String target) {
        if (target != null && !target.isBlank()) {
            return target.strip();
        }
        return text.chars().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff)
                ? "英文"
                : TextAction.DEFAULT_TARGET;
    }
}
