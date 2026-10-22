package com.ainotes.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 工具表本身的小逻辑:中文名、写操作名单、确认卡片上的那一行摘要、标签写法。
 *
 * <p>摘要看着是"文案",其实是**安全相关**的:用户就是照着它决定点不点确认。所以它必须
 * 直接取自模型给的参数(而不是等工具执行后再总结),这几个断言就是钉住这一点。
 */
class AgentToolTest {

    @Test
    void writeToolsAreExactlyTheOnesThatChangeNotes() {
        assertThat(AgentTool.CREATE_NOTE.isWrite()).isTrue();
        assertThat(AgentTool.UPDATE_NOTE.isWrite()).isTrue();
        assertThat(AgentTool.ADD_TAGS.isWrite()).isTrue();
        assertThat(AgentTool.REMEMBER_FACT.isWrite()).isTrue();
        assertThat(AgentTool.SEARCH_NOTES.isWrite()).isFalse();
        assertThat(AgentTool.GET_NOTE.isWrite()).isFalse();
        assertThat(AgentTool.LIST_NOTES.isWrite()).isFalse();
        assertThat(AgentTool.UNKNOWN.isWrite()).isFalse();
        assertThat(AgentTool.isWrite("createNote")).isTrue();
        assertThat(AgentTool.isWrite("searchNotes")).isFalse();
        // 模型编出来的名字不该被当成写操作(否则会把整轮对话卡在确认上)
        assertThat(AgentTool.isWrite("dropDatabase")).isFalse();
    }

    @Test
    void unknownToolNamesSurviveIntoTheTrace() {
        assertThat(AgentTool.of("frobnicate")).isEqualTo(AgentTool.UNKNOWN);
        assertThat(AgentTool.labelOf("frobnicate")).isEqualTo("frobnicate");
        assertThat(AgentTool.labelOf("createNote")).isEqualTo("新建笔记");
        assertThat(AgentTool.describe("frobnicate", Map.of())).isEqualTo("调用 frobnicate");
    }

    @Test
    void describeSummarisesWhatWillHappen() {
        assertThat(AgentTool.describe("searchNotes", Map.of("query", "本周工作")))
                .isEqualTo("检索「本周工作」");
        assertThat(AgentTool.describe("createNote", Map.of("title", "周报", "contentMd", "x")))
                .isEqualTo("新建《周报》");
        assertThat(AgentTool.describe("updateNote", Map.of("noteId", "abc", "appendText", "补一句")))
                .isEqualTo("修改 笔记 abc(追加)");
        assertThat(AgentTool.describe("updateNote", Map.of("noteId", "abc", "contentMd", "整篇")))
                .isEqualTo("修改 笔记 abc(替换正文)");
        assertThat(AgentTool.describe("getNote", Map.of("title", "架构设计")))
                .isEqualTo("读取 《架构设计》");
        assertThat(AgentTool.describe("listNotes", Map.of("sinceDays", 7)))
                .isEqualTo("列出笔记 · 最近 7 天");
        // sinceDays=0 是"不限时间",不该在卡片上写成"最近 0 天"
        assertThat(AgentTool.describe("listNotes", Map.of("sinceDays", 0))).isEqualTo("列出笔记");
        assertThat(AgentTool.describe("addTags", Map.of("noteId", "abc", "tags", List.of("工作", "周报"))))
                .isEqualTo("给 笔记 abc 打标签 工作 周报");
        // 参数缺了就写"(未给)",而不是抛异常 —— 摘要不该成为运行失败的原因
        assertThat(AgentTool.describe("createNote", Map.of())).isEqualTo("新建《(未给)》");
    }

    @Test
    void longArgumentsAreTruncatedInTheSummary() {
        String longQuery = "很长很长".repeat(30);
        String summary = AgentTool.describe("searchNotes", Map.of("query", longQuery));
        assertThat(summary).hasSizeLessThan(60).contains("…");
    }

    @Test
    void tagsAreNormalisedFromWhateverShapeTheModelSends() {
        assertThat(AgentToolSet.tagLine("周报 工作")).isEqualTo("#周报 #工作");
        assertThat(AgentToolSet.tagLine("周报,工作、总结;done")).isEqualTo("#周报 #工作 #总结 #done");
        assertThat(AgentToolSet.tagLine("#周报 #工作")).isEqualTo("#周报 #工作");
        assertThat(AgentToolSet.tagLine("1. 周报\n2. 工作")).isEqualTo("#周报 #工作");
        assertThat(AgentToolSet.tagLine("周报 周报")).isEqualTo("#周报"); // 去重
        assertThat(AgentToolSet.tagLine(null)).isEmpty();
        assertThat(AgentToolSet.tagLine("   ")).isEmpty();
        // 超过 12 个字的不是标签,是句子 —— 丢掉,别把一整句话塞进正文
        assertThat(AgentToolSet.tagLine("这是一句特别长的话不该被当成标签")).isEmpty();
    }
}
