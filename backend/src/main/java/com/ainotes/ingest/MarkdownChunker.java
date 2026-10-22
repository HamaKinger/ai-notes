package com.ainotes.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 分块器(对应设计文档 §6.2)。
 *
 * <p>策略:
 * <ol>
 *   <li>按 ATX 标题(# ~ ######)切分,记录标题路径 heading_path 作为上下文前缀;</li>
 *   <li>章节过大时按空行(段落)继续切,单块不超过 maxChars;</li>
 *   <li>单段过长时硬切,避免出现超大块。</li>
 * </ol>
 *
 * <p>M3 会在这些块之上生成 embedding 并写入 ES 的 dense_vector 字段。
 */
public final class MarkdownChunker {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

    private MarkdownChunker() {
    }

    public static List<ChunkPayload> chunk(String markdown, int maxChars) {
        List<ChunkPayload> chunks = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return chunks;
        }

        List<String> headingStack = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        String headingPath = null;

        for (String line : markdown.split("\n", -1)) {
            Matcher matcher = HEADING.matcher(line);
            if (matcher.matches()) {
                // 遇到新标题:先把上一节的内容落块
                emit(chunks, buffer, headingPath, maxChars);

                int level = matcher.group(1).length();
                String title = matcher.group(2).trim();
                while (headingStack.size() >= level) {
                    headingStack.remove(headingStack.size() - 1);
                }
                while (headingStack.size() < level - 1) {
                    headingStack.add("");
                }
                headingStack.add(title);
                headingPath = headingStack.stream().filter(s -> !s.isBlank()).reduce((a, b) -> a + " / " + b).orElse(null);
            } else {
                buffer.append(line).append('\n');
            }
        }
        emit(chunks, buffer, headingPath, maxChars);
        return chunks;
    }

    private static void emit(List<ChunkPayload> chunks, StringBuilder buffer, String headingPath, int maxChars) {
        String text = buffer.toString().trim();
        buffer.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        for (String piece : splitByParagraph(text, maxChars)) {
            chunks.add(new ChunkPayload(chunks.size(), headingPath, piece));
        }
    }

    private static List<String> splitByParagraph(String text, int maxChars) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.split("\n\\s*\n")) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            for (String part : hardSplit(trimmed, maxChars)) {
                if (current.length() > 0 && current.length() + part.length() + 2 > maxChars) {
                    pieces.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) {
                    current.append("\n\n");
                }
                current.append(part);
            }
        }
        if (current.length() > 0) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    private static List<String> hardSplit(String paragraph, int maxChars) {
        if (paragraph.length() <= maxChars) {
            return List.of(paragraph);
        }
        List<String> parts = new ArrayList<>();
        for (int start = 0; start < paragraph.length(); start += maxChars) {
            parts.add(paragraph.substring(start, Math.min(paragraph.length(), start + maxChars)));
        }
        return parts;
    }
}
