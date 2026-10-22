package com.ainotes.ai;

import com.ainotes.search.RetrievedChunk;
import java.util.UUID;

/**
 * 一段被引用的笔记片段。<b>{@code index} 就是回答里 {@code [1]}、{@code [2]} 的编号</b>,
 * 从 1 开始 —— prompt 里的编号、引用 chip 的顺序、落库的 {@code citation.ord} 都用它,
 * 三者必须一致,否则界面上点 [2] 会跳到第 3 段去。
 *
 * <p>这是检索结果到"引用"之间的那一层:{@code content} 是原文(prompt 用),
 * {@link #snippet()} 是截断后的快照(落库 + 界面定位用)。
 */
public record ChatSource(
        int index,
        String chunkId,
        UUID noteId,
        String noteTitle,
        String headingPath,
        String content,
        double score) {

    /**
     * 快照里存多长的原文。
     *
     * <p>跳转定位只用得上开头几十个字(lib/locate.ts 的锚点会挑一行里够长的一段文本),
     * 存整段 800 字纯属浪费;截短一点,引用表也还看得清。
     */
    static final int SNIPPET_MAX = 300;

    public static ChatSource of(int index, RetrievedChunk chunk, String noteTitle) {
        return new ChatSource(
                index,
                chunk.chunkId(),
                chunk.noteId(),
                noteTitle,
                chunk.headingPath(),
                stripHighlight(chunk.content()),
                chunk.score());
    }

    /**
     * 去掉检索层加的高亮标记(`<em>` / `</em>`)。
     *
     * <p>关键词分支返回的 `content` 是**带高亮**的片段(它还要拿去画检索结果列表里的黄条)。
     * 这两个用途都不能留标签:① 喂给模型时 `<em>的</em>` 只是噪音,模型偶尔还会把它当成
     * 格式指令;② 引用跳转是拿片段里的**字面量**去正文里找,带着标签永远匹配不上
     * (实测表现:点引用切了笔记、却没高亮)。
     */
    static String stripHighlight(String content) {
        return content == null ? null : content.replaceAll("</?em>", "");
    }

    /** 落库/界面用的原文快照(可能为 null:chunk 没正文)。 */
    public String snippet() {
        if (content == null || content.isBlank()) {
            return null;
        }
        String flat = content.strip();
        return flat.length() <= SNIPPET_MAX ? flat : flat.substring(0, SNIPPET_MAX) + "…";
    }
}
