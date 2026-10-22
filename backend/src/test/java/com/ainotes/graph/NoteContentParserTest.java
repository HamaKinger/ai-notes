package com.ainotes.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class NoteContentParserTest {

    private final NoteContentParser parser = new NoteContentParser();

    private List<String> linkTargets(String markdown) {
        return parser.parse(markdown).links().stream().map(WikiLink::target).toList();
    }

    @Test
    void extractsPlainWikiLink() {
        assertThat(linkTargets("见 [[架构设计]] 一节")).containsExactly("架构设计");
    }

    @Test
    void stripsAliasHeadingAndFolderPrefix() {
        assertThat(linkTargets("[[架构设计|设计文档]]")).containsExactly("架构设计");
        assertThat(linkTargets("[[架构设计#检索层]]")).containsExactly("架构设计");
        assertThat(linkTargets("[[技术/架构设计]]")).containsExactly("架构设计");
    }

    @Test
    void deduplicatesRepeatedTargetsAndKeepsFirstContext() {
        ParsedNote parsed = parser.parse("[[架构设计]]\n\n稍后再看 [[架构设计|设计]]");

        assertThat(parsed.links()).hasSize(1);
        assertThat(parsed.links().get(0).target()).isEqualTo("架构设计");
        assertThat(parsed.links().get(0).context()).startsWith("[[架构设计]]");
    }

    @Test
    void ignoresEmbeds() {
        assertThat(linkTargets("![[图片.png]]")).isEmpty();
    }

    @Test
    void ignoresLinksInsideCode() {
        String markdown = """
                行内代码 `[[不算]]`

                ```markdown
                [[代码块里的也不算]]
                ```

                [[算]]
                """;

        assertThat(linkTargets(markdown)).containsExactly("算");
    }

    @Test
    void ignoresLinksInsideHtml() {
        assertThat(linkTargets("<div>[[不算]]</div>")).isEmpty();
    }

    @Test
    void extractsTagsPrecededByWhitespaceOrLineStart() {
        ParsedNote parsed = parser.parse("#ai-notes 与 #项目/检索 都在\n下一行的 #标签");

        assertThat(parsed.tags()).containsExactly("ai-notes", "项目/检索", "标签");
    }

    @Test
    void doesNotTreatWordsOrHeadingsAsTags() {
        // a#b 不是标签;ATX 标题的 # 在 AST 里已被剥离
        ParsedNote parsed = parser.parse("a#b 不是标签\n\n# 一级标题\n\n## 二级标题");

        assertThat(parsed.tags()).isEmpty();
    }

    @Test
    void doesNotTreatTagsInsideCodeAsTags() {
        ParsedNote parsed = parser.parse("`#不算标签`\n\n```\n#也不算\n```\n\n#算");

        assertThat(parsed.tags()).containsExactly("算");
    }

    @Test
    void handlesEmptyAndNullInput() {
        assertThat(parser.parse(null).links()).isEmpty();
        assertThat(parser.parse("").tags()).isEmpty();
        assertThat(parser.parse("   ").links()).isEmpty();
    }
}
