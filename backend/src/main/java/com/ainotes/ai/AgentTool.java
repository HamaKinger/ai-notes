package com.ainotes.ai;

import java.util.Map;

/**
 * 工具清单(M5 Step 1)。
 *
 * <p>这个枚举管的是"工具之外的那点事":中文名、是不是写操作、以及**确认卡片上那一行摘要**。
 * 工具本身(参数 schema、描述、怎么执行)在 {@link AgentToolSet} 里用 {@code @Tool} 标注 ——
 * 那里必须和模型看到的定义贴在一起,而这里要的是"任意名字都能查"的静态表。
 *
 * <p><b>为什么要单独列一份写操作名单</b>:写操作要拦下来等用户确认,而"拦"发生在**执行之前**,
 * 那时还没有工具的执行结果、拿不到工具自己写的摘要。所以标签与"是否写操作"必须能脱离实例查到
 * (回放历史轨迹时也一样:工具集将来会变,不能拿今天的规则去解释昨天的调用)。
 *
 * <p>清单之外的写法会落到 {@link #UNKNOWN}:模型偶尔会编一个不存在的工具名,那时要回一句
 * "没有这个工具",而不是抛异常把整轮对话打断。
 */
public enum AgentTool {

    SEARCH_NOTES("searchNotes", "搜索笔记", false),
    GET_NOTE("getNote", "读取笔记", false),
    LIST_NOTES("listNotes", "列出笔记", false),
    CREATE_NOTE("createNote", "新建笔记", true),
    UPDATE_NOTE("updateNote", "修改笔记", true),
    ADD_TAGS("addTags", "打标签", true),
    REMEMBER_FACT("rememberFact", "记住事实", true),

    /** 模型编出来的工具名(或旧版本留下的轨迹)。 */
    UNKNOWN("", "未知工具", false);

    /** 摘要里单个参数值最多显示多少字 */
    private static final int VALUE_MAX = 40;

    private final String wire;
    private final String label;
    private final boolean write;

    AgentTool(String wire, String label, boolean write) {
        this.wire = wire;
        this.label = label;
        this.write = write;
    }

    public String wire() {
        return wire;
    }

    public String label() {
        return label;
    }

    /** 写操作:执行前必须经用户确认。 */
    public boolean isWrite() {
        return write;
    }

    /** 按模型给的名字查;查不到返回 {@link #UNKNOWN}(不抛异常)。 */
    public static AgentTool of(String name) {
        if (name != null) {
            for (AgentTool tool : values()) {
                if (tool.wire.equals(name)) {
                    return tool;
                }
            }
        }
        return UNKNOWN;
    }

    /** 轨迹/事件里的中文名;未知工具原样返回模型给的名字,排查时才知道它编的是什么。 */
    public static String labelOf(String name) {
        AgentTool tool = of(name);
        return tool == UNKNOWN ? (name == null ? "未知工具" : name) : tool.label;
    }

    public static boolean isWrite(String name) {
        return of(name).isWrite();
    }

    /**
     * 一句话摘要:"谁要做什么"。写操作的确认卡片、轨迹里的每一行都用它。
     *
     * <p>参数直接从模型给的 JSON 里取,**不做校验**(校验是执行阶段的事):摘要只是给人看的,
     * 参数缺了、类型不对,执行时会带着明确的错误回来。
     */
    public static String describe(String name, Map<String, Object> args) {
        AgentTool tool = of(name);
        return switch (tool) {
            case SEARCH_NOTES -> "检索「" + value(args, "query") + "」";
            case GET_NOTE -> "读取 " + noteRef(args);
            case LIST_NOTES -> "列出笔记" + listFilter(args);
            case CREATE_NOTE -> "新建《" + value(args, "title") + "》";
            case UPDATE_NOTE -> "修改 " + noteRef(args) + (has(args, "appendText") ? "(追加)" : "(替换正文)");
            case ADD_TAGS -> "给 " + noteRef(args) + " 打标签 " + value(args, "tags");
            case REMEMBER_FACT -> "记住:" + value(args, "fact");
            case UNKNOWN -> "调用 " + name;
        };
    }

    private static String noteRef(Map<String, Object> args) {
        if (has(args, "title")) {
            return "《" + value(args, "title") + "》";
        }
        return "笔记 " + value(args, "noteId");
    }

    private static String listFilter(Map<String, Object> args) {
        StringBuilder out = new StringBuilder();
        // sinceDays=0 等于"不限时间",不该在卡片上显示成"最近 0 天"
        if (positive(args, "sinceDays")) {
            out.append(" · 最近 ").append(value(args, "sinceDays")).append(" 天");
        }
        if (has(args, "tag")) {
            out.append(" · 标签 #").append(value(args, "tag"));
        }
        return out.toString();
    }

    /** 数字参数大于 0 才算给了(0 / 负数 / 不是数字都当没给)。 */
    private static boolean positive(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (v instanceof Number number) {
            return number.doubleValue() > 0;
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.strip()) > 0;
            } catch (NumberFormatException notANumber) {
                return false;
            }
        }
        return false;
    }

    private static boolean has(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (v == null) {
            return false;
        }
        return !(v instanceof String s) || !s.isBlank();
    }

    /** 取参数值并截断;列表就逗号连起来(标签是数组)。 */
    private static String value(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        String text;
        if (v instanceof Iterable<?> items) {
            StringBuilder joined = new StringBuilder();
            for (Object item : items) {
                if (joined.length() > 0) {
                    joined.append(' ');
                }
                joined.append(item);
            }
            text = joined.toString();
        } else {
            text = v == null ? "(未给)" : String.valueOf(v);
        }
        text = text.strip();
        if (text.isEmpty()) {
            return "(空)";
        }
        return text.length() <= VALUE_MAX ? text : text.substring(0, VALUE_MAX) + "…";
    }
}
