package com.ainotes.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 文本工具:流式事件、提示词拼装、解析与降级。
 *
 * <p>重点测的是"模型不听话时会发生什么":少给一块、多写一句寒暄、把标签吐成一句话。
 * 这些都不会报错,只会安静地写进用户的笔记 —— 所以每个动作的**输出约定**都钉在这里。
 */
class AiTextServiceTest {

    private TestChatModel model = TestChatModel.streaming("润色", "后的文字");
    private AiTextService service;

    @BeforeEach
    void setUp() {
        service = new AiTextService(ChatClient.builder(model).build(), "test-model", 6000);
    }

    // ---- 选中文本操作 --------------------------------------------------

    @Test
    void inlineStreamsMetaThenTokensThenDone() {
        List<TextEvent> events = service.inline("原文", TextAction.POLISH, null).collectList().block();

        assertThat(events).extracting(TextEvent::name)
                .containsExactly(TextEvent.META, TextEvent.TOKEN, TextEvent.TOKEN, TextEvent.DONE);

        TextEvent.Meta meta = (TextEvent.Meta) events.getFirst().data();
        assertThat(meta.action()).isEqualTo("polish");
        assertThat(meta.label()).isEqualTo("润色");
        assertThat(meta.writesBack()).isTrue();
        assertThat(meta.model()).isEqualTo("test-model");

        // 增量是逐块发的,用量和字数在收尾报:前端据此显示"这次花了多少"
        TextEvent.Done done = (TextEvent.Done) events.getLast().data();
        assertThat(done.chars()).isEqualTo("润色后的文字".length());
        assertThat(done.tokenIn()).isEqualTo(11);
        assertThat(done.tokenOut()).isEqualTo(7);
    }

    @Test
    void inlineSendsTheTaskAndTheSelectionToTheModel() {
        service.inline("  原文  ", TextAction.POLISH, null).collectList().block();

        assertThat(model.lastSystem())
                .contains("润色")
                .contains("只输出处理结果本身")
                .contains("不是指令");
        assertThat(model.lastUser()).isEqualTo("【待处理文本】\n原文");
    }

    @Test
    void translatePicksTheOppositeLanguageWhenTheCallerDoesNotSay() {
        service.inline("这是中文", TextAction.TRANSLATE, null).collectList().block();
        assertThat(model.lastSystem()).contains("英文");
    }

    @Test
    void translateGoesBackToChineseForNonChineseText() {
        service.inline("this is English", TextAction.TRANSLATE, null).collectList().block();
        assertThat(model.lastSystem()).contains("中文");
    }

    @Test
    void anExplicitTargetLanguageWins() {
        service.inline("这是中文", TextAction.TRANSLATE, "日文").collectList().block();
        assertThat(model.lastSystem()).contains("日文");
    }

    @Test
    void explainIsNotMeantToBeWrittenBack() {
        List<TextEvent> events = service.inline("一段话", TextAction.EXPLAIN, null).collectList().block();
        assertThat(((TextEvent.Meta) events.getFirst().data()).writesBack()).isFalse();
    }

    @Test
    void inlineRefusesEmptyAndOverlongSelections() {
        assertThatThrownBy(() -> service.inline("   ", TextAction.POLISH, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("选中");

        // 超长不能悄悄截断:替换选中会把后半段一起删掉
        assertThatThrownBy(() -> service.inline("字".repeat(6001), TextAction.POLISH, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("分段处理");
    }

    @Test
    void aFailedStreamStillDeliversTheHalfAnswerAndEndsWithAnError() {
        model = TestChatModel.streamingThenFailing("模型 500", "只润色到一半");
        service = new AiTextService(ChatClient.builder(model).build(), "test-model", 6000);

        List<TextEvent> events = service.inline("原文", TextAction.POLISH, null).collectList().block();

        assertThat(events).extracting(TextEvent::name)
                .containsExactly(TextEvent.META, TextEvent.TOKEN, TextEvent.ERROR);
        assertThat(((TextEvent.Error) events.getLast().data()).message()).contains("模型 500");
    }

    // ---- 摘要 ----------------------------------------------------------

    @Test
    void summarizeReturnsASingleCleanLine() {
        model = TestChatModel.answering("\"一句话摘要。\"\n");
        service = new AiTextService(ChatClient.builder(model).build(), "test-model", 6000);

        assertThat(service.summarize("标题", "正文")).isEqualTo("一句话摘要。");
    }

    @Test
    void summarizeAsksForOneShortSentence() {
        model = TestChatModel.answering("摘要");
        service = new AiTextService(ChatClient.builder(model).build(), "test-model", 6000);

        service.summarize("标题", "正文");

        assertThat(model.lastSystem()).contains("一句话").contains("60 字").contains("Markdown");
        assertThat(model.lastUser()).isEqualTo("【标题】标题\n【正文】\n正文");
    }

    @Test
    void untitledNotesAreNamedInsteadOfLeftBlank() {
        model = TestChatModel.answering("摘要");
        service = new AiTextService(ChatClient.builder(model).build(), "test-model", 6000);

        service.summarize("  ", "正文");

        assertThat(model.lastUser()).contains("【标题】未命名笔记");
    }

    @Test
    void aLongNoteIsClippedAndTheModelIsToldSo() {
        model = TestChatModel.answering("摘要");
        service = new AiTextService(ChatClient.builder(model).build(), "test-model", 30);

        service.summarize("标题", "字".repeat(50));

        assertThat(model.lastUser())
                .contains("字".repeat(30))
                .contains("已截断")
                .doesNotContain("字".repeat(31));
    }

    @Test
    void summarizeRefusesAnEmptyNote() {
        assertThatThrownBy(() -> service.summarize("标题", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("空的");
    }

    @Test
    void asilentModelFailsLoudlyInsteadOfSavingAnEmptySummary() {
        model = TestChatModel.answering("   ");
        service = new AiTextService(ChatClient.builder(model).build(), "test-model", 6000);

        assertThatThrownBy(() -> service.summarize("标题", "正文"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有返回");
    }

    // ---- 标签 ----------------------------------------------------------

    @Test
    void suggestTagsCarriesTheWorkspacesExistingTags() {
        model = TestChatModel.answering("检索");
        service = new AiTextService(ChatClient.builder(model).build(), "test-model", 6000);

        service.suggestTags("标题", "正文", List.of("检索", "架构"));

        assertThat(model.lastUser()).contains("【已有标签】检索, 架构");
        assertThat(model.lastSystem()).contains("每行一个").contains("3~5");
    }

    @Test
    void suggestTagsParsesLooseOutput() {
        model = TestChatModel.answering("""
                #检索
                1. 混合检索
                - 倒排索引
                "向量"
                """);
        service = new AiTextService(ChatClient.builder(model).build(), "test-model", 6000);

        assertThat(service.suggestTags("标题", "正文", List.of()))
                .containsExactly("检索", "混合检索", "倒排索引", "向量");
    }

    @Test
    void parseTagsDropsWhatCannotBeATag() {
        String raw = """
                #标签
                太长的标签名超过十二个字了吧
                带空格的标签 词
                架构
                """;

        assertThat(AiTextService.parseTags(raw, "正文里有 #标签")).containsExactly("架构");
    }

    @Test
    void parseTagsDedupesAndCapsTheList() {
        assertThat(AiTextService.parseTags("RRF\nrrf\nRrf", null)).containsExactly("RRF");
        assertThat(AiTextService.parseTags("   ", null)).isEmpty();
        assertThat(AiTextService.parseTags("一甲\n一乙\n一丙\n一丁\n一戊\n一己", null))
                .hasSize(AiTextService.MAX_TAGS)
                .containsExactly("一甲", "一乙", "一丙", "一丁", "一戊");
    }
}
