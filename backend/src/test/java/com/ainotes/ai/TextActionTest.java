package com.ainotes.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 选中文本操作的解析与任务说明。
 *
 * <p>这里承载两条容易悄悄出错的约定:一是**没有默认动作** —— 漏传 action 时必须报错,
 * 猜一个"润色"就意味着用户点翻译却拿到润色过的中文;二是**只有解释不写回笔记** ——
 * 把解释替换回正文,笔记就变成了问答记录。
 */
class TextActionTest {

    @Test
    void parsesEnglishAndChineseSpellings() {
        assertThat(TextAction.of("polish")).isEqualTo(TextAction.POLISH);
        assertThat(TextAction.of(" TRANSLATE ")).isEqualTo(TextAction.TRANSLATE);
        assertThat(TextAction.of("续写")).isEqualTo(TextAction.CONTINUE);
        assertThat(TextAction.of("摘要")).isEqualTo(TextAction.SUMMARIZE);
    }

    @Test
    void refusesToGuessWhenTheActionIsMissingOrUnknown() {
        assertThatThrownBy(() -> TextAction.of(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("polish");
        assertThatThrownBy(() -> TextAction.of(" rewrite "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知的文本操作");
    }

    @Test
    void translateNeedsATargetAndDefaultsToChinese() {
        assertThat(TextAction.TRANSLATE.instruction(null)).contains("中文");
        assertThat(TextAction.TRANSLATE.instruction("  ")).contains("中文");
        assertThat(TextAction.TRANSLATE.instruction("日文")).contains("日文");
    }

    @Test
    void otherActionsIgnoreTheTargetLanguage() {
        assertThat(TextAction.POLISH.instruction("日文")).doesNotContain("日文");
    }

    @Test
    void everyActionTellsTheModelWhatToDo() {
        for (TextAction action : TextAction.values()) {
            assertThat(action.instruction(null)).as("%s 缺少任务说明", action).isNotBlank();
            assertThat(action.label()).isNotBlank();
            assertThat(action.wire()).isEqualTo(action.name().toLowerCase());
        }
    }

    @Test
    void onlyExplainIsNotWrittenBack() {
        assertThat(TextAction.EXPLAIN.writesBack()).isFalse();
        assertThat(TextAction.POLISH.writesBack()).isTrue();
        assertThat(TextAction.TRANSLATE.writesBack()).isTrue();
        assertThat(TextAction.CONTINUE.writesBack()).isTrue();
        assertThat(TextAction.SUMMARIZE.writesBack()).isTrue();
    }
}
