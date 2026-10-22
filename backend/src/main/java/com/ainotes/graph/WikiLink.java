package com.ainotes.graph;

/**
 * 一条 `[[双链]]` 引用。
 *
 * @param target  目标标题(已去掉 `|别名`、`#小节`、`目录/` 前缀,并 trim)
 * @param context 首次出现处的上下文片段(用于反链预览)
 */
public record WikiLink(String target, String context) {
}
