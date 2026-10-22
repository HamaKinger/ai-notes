package com.ainotes.graph;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

/**
 * 从 Markdown 正文里抽取双链与标签。
 *
 * <p>Markdown 解析用 CommonMark 官方 Java 实现(`org.commonmark:commonmark`),
 * 不自己写解析器。做法是先得到 AST,再只在 {@code Text} 节点上匹配:
 * <ul>
 *   <li>代码块({@code FencedCodeBlock} / {@code IndentedCodeBlock})、行内代码({@code Code})
 *       与原始 HTML 会被整棵子树跳过 —— 所以教程里写的 {@code [[示例]]} 不会被误当成双链;</li>
 *   <li>{@code #标签} 要求前面是行首或空白,因此 {@code a#b} 不算标签;
 *       而 ATX 标题(`# 标题`)在 AST 里 `#` 已被剥离,也不会被误判成标签。</li>
 * </ul>
 *
 * <p>`[[...]]` 是 Obsidian 风格的扩展语法,不属于 CommonMark,因此这里按应用层规则处理。
 */
@Component
public class NoteContentParser {

    private static final Parser PARSER = Parser.builder().build();

    /** `[[目标]]` / `[[目标|别名]]` / `[[目标#小节]]` / `[[目录/目标]]` */
    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[([^\\[\\]\\n]+)]]");

    /** `#标签`,支持中文、数字、下划线、连字符与嵌套斜杠 */
    private static final Pattern TAG = Pattern.compile("(?:^|\\s)#([\\p{L}\\p{N}_\\-/]+)");

    private static final int CONTEXT_WINDOW = 80;

    public ParsedNote parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return ParsedNote.empty();
        }
        Collector collector = new Collector();
        walk(PARSER.parse(markdown), collector);
        return new ParsedNote(List.copyOf(collector.tags), List.copyOf(collector.links.values()));
    }

    private static void walk(Node node, Collector collector) {
        if (node instanceof Code
                || node instanceof FencedCodeBlock
                || node instanceof IndentedCodeBlock
                || node instanceof HtmlInline
                || node instanceof HtmlBlock) {
            return;
        }
        if (node instanceof Text text) {
            scan(text.getLiteral(), collector);
            return;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            walk(child, collector);
        }
    }

    private static void scan(String literal, Collector collector) {
        if (literal == null || literal.isEmpty()) {
            return;
        }

        Matcher tagMatcher = TAG.matcher(literal);
        while (tagMatcher.find()) {
            collector.tags.add(tagMatcher.group(1));
        }

        Matcher linkMatcher = WIKI_LINK.matcher(literal);
        while (linkMatcher.find()) {
            int start = linkMatcher.start();
            if (start > 0 && literal.charAt(start - 1) == '!') {
                // ![[...]] 是嵌入,不计入双链
                continue;
            }
            String target = normalizeTarget(linkMatcher.group(1));
            if (target != null) {
                collector.links.putIfAbsent(
                        target.toLowerCase(Locale.ROOT),
                        new WikiLink(target, context(literal, start, linkMatcher.end())));
            }
        }
    }

    /** `目标|别名` → 目标;`目标#小节` → 目标;`目录/目标` → 目标。 */
    private static String normalizeTarget(String raw) {
        String body = raw.trim();

        int pipe = body.indexOf('|');
        if (pipe >= 0) {
            body = body.substring(0, pipe).trim();
        }
        int hash = body.indexOf('#');
        if (hash >= 0) {
            body = body.substring(0, hash).trim();
        }
        int slash = body.lastIndexOf('/');
        if (slash >= 0) {
            body = body.substring(slash + 1).trim();
        }
        return body.isEmpty() ? null : body;
    }

    private static String context(String literal, int start, int end) {
        int from = Math.max(0, start - CONTEXT_WINDOW);
        int to = Math.min(literal.length(), end + CONTEXT_WINDOW);
        String snippet = literal.substring(from, to).replaceAll("\\s+", " ").trim();
        return snippet.length() > 200 ? snippet.substring(0, 200) : snippet;
    }

    private static final class Collector {
        private final Set<String> tags = new LinkedHashSet<>();
        private final Map<String, WikiLink> links = new LinkedHashMap<>();
    }
}
