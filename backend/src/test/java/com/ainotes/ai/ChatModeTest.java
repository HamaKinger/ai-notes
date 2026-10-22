package com.ainotes.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 模式解析。默认必须是 RAG:侧栏的卖点是"问自己的笔记",前端偶尔漏传 mode 时
 * 不该悄悄退化成普通聊天 —— 那会让人以为知识库没检索到东西。
 */
class ChatModeTest {

    @Test
    void defaultsToRag() {
        assertThat(ChatMode.of(null)).isEqualTo(ChatMode.RAG);
        assertThat(ChatMode.of("  ")).isEqualTo(ChatMode.RAG);
    }

    @Test
    void acceptsBothSpellingsCaseInsensitively() {
        assertThat(ChatMode.of("RAG")).isEqualTo(ChatMode.RAG);
        assertThat(ChatMode.of("chat")).isEqualTo(ChatMode.CHAT);
        assertThat(ChatMode.of(" CHAT ")).isEqualTo(ChatMode.CHAT);
    }

    @Test
    void explainsItselfWhenTheModeIsUnknown() {
        assertThatThrownBy(() -> ChatMode.of("tool")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wireFormIsLowercase() {
        assertThat(ChatMode.RAG.wire()).isEqualTo("rag");
        assertThat(ChatMode.CHAT.wire()).isEqualTo("chat");
    }
}
