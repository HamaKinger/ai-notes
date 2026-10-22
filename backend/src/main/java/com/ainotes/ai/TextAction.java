package com.ainotes.ai;

/**
 * 选中文本上的操作(设计文档 §2.3「选中文本操作」)。
 *
 * <p>每个动作都自带一句**给模型的任务说明**:说明集中在这里,而不是散在 Service 的
 * if/else 里 —— 加一个动作时只改这一处,也不会出现"界面上的标签改了、提示词忘了改"。
 *
 * <p>约定(整个文本工具都靠它成立):**模型只输出结果本身**。所以润色/翻译的结果可以直接
 * 替换选中文本,续写可以直接接在后面;唯一例外是 {@link #EXPLAIN} —— 它本来就是解释,
 * 结果只给人看,不该写回笔记。
 */
public enum TextAction {

    POLISH("润色", """
            把【待处理文本】润色成通顺、简洁的书面表达:改掉错别字与病句、删掉口水话与重复,
            保持原意、语气和 Markdown 结构不变,长度与原文相当。"""),

    TRANSLATE("翻译", """
            把【待处理文本】翻译成%s:只输出译文,术语要准确,保留 Markdown 结构、
            代码片段、`[[双链]]`、`#标签` 与链接。"""),

    CONTINUE("续写", """
            顺着【待处理文本】的内容与语气继续写下去:写 1~2 段,直接接在原文后面,
            不要重复原文,也不要总结或解释你写了什么。"""),

    SUMMARIZE("总结", """
            把【待处理文本】压缩成要点:用 Markdown 无序列表,每条一句话,最多 5 条,
            只保留原文里真正写了的结论与事实。"""),

    EXPLAIN("解释", """
            解释【待处理文本】:先说它讲的是什么,再解释其中的术语、难句或背景,
            可以用列表;这是唯一允许输出解释文字的场景。""");

    /** 没说翻译成什么时的默认目标(选中的是中文就译英文,见 AiTextService#targetOr) */
    public static final String DEFAULT_TARGET = "中文";

    private final String label;
    private final String instruction;

    TextAction(String label, String instruction) {
        this.label = label;
        this.instruction = instruction;
    }

    /**
     * 解析请求里的 action。中英文都收:前端发英文,curl 手测时中文更顺手。
     */
    public static TextAction of(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("缺少 action(可选 polish / translate / continue / summarize / explain)");
        }
        return switch (raw.trim().toLowerCase()) {
            case "polish", "proofread", "润色" -> POLISH;
            case "translate", "translation", "翻译" -> TRANSLATE;
            case "continue", "expand", "续写" -> CONTINUE;
            case "summarize", "summary", "总结", "摘要" -> SUMMARIZE;
            case "explain", "解释", "讲解" -> EXPLAIN;
            default -> throw new IllegalArgumentException(
                    "未知的文本操作: " + raw + "(可选 polish / translate / continue / summarize / explain)");
        };
    }

    /** 传给前端的写法(小写,与请求里的一致)。 */
    public String wire() {
        return name().toLowerCase();
    }

    public String label() {
        return label;
    }

    /** 本次任务说明;只有翻译需要目标语言。 */
    public String instruction(String target) {
        if (this != TRANSLATE) {
            return instruction.strip();
        }
        String language = target == null || target.isBlank() ? DEFAULT_TARGET : target.strip();
        return instruction.formatted(language).strip();
    }

    /** 结果该不该写回笔记:解释是给人看的,替换回去等于把笔记改成问答记录。 */
    public boolean writesBack() {
        return this != EXPLAIN;
    }
}
