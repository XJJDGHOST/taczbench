package com.taczbench.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个带源码位置信息的 JSON 节点。
 * <p>
 * 之所以不用 Gson 的 JsonElement，是因为枪械数据文件里全是中文注释和自定义排版，
 * 而 Gson 解析后重新序列化会把注释全部吃掉。这里保留 {@code start}/{@code end}
 * 两个下标，改写时只替换目标值的字节区间，其余文本（注释、空行、缩进）原样保留。
 */
public final class JsonNode {

    public enum Kind { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }

    final Kind kind;
    /** 值在源码中的起始下标（含）。 */
    final int start;
    /** 值在源码中的结束下标（不含）。解析完成时填充。 */
    int end;
    /** 源码原样子串。解析完成时填充。 */
    String raw;
    /** STRING 时是解码后的内容；其它类型是字面量。 */
    String text;

    final List<String> keys = new ArrayList<>();
    final List<JsonNode> children = new ArrayList<>();
    final Map<String, JsonNode> members = new LinkedHashMap<>();

    JsonNode(Kind kind, int start) {
        this.kind = kind;
        this.start = start;
        this.end = start;
    }

    public Kind kind() { return kind; }

    public boolean isObject() { return kind == Kind.OBJECT; }

    public boolean isArray() { return kind == Kind.ARRAY; }

    public boolean isNumber() { return kind == Kind.NUMBER; }

    public boolean isString() { return kind == Kind.STRING; }

    public boolean isBoolean() { return kind == Kind.BOOLEAN; }

    public boolean isContainer() { return kind == Kind.OBJECT || kind == Kind.ARRAY; }

    public int start() { return start; }

    public int end() { return end; }

    public String raw() { return raw; }

    public List<String> keys() { return keys; }

    public List<JsonNode> children() { return children; }

    public int size() { return children.size(); }

    public JsonNode member(String key) { return members.get(key); }

    public boolean has(String key) { return members.containsKey(key); }

    public JsonNode item(int index) {
        return index >= 0 && index < children.size() ? children.get(index) : null;
    }

    public String asString() {
        return text == null ? "" : text;
    }

    public double asDouble() {
        if (kind == Kind.NUMBER) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return 0.0D;
            }
        }
        if (kind == Kind.BOOLEAN) {
            return Boolean.parseBoolean(text) ? 1.0D : 0.0D;
        }
        return 0.0D;
    }

    public int asInt() {
        return (int) Math.round(asDouble());
    }

    public boolean asBoolean() {
        return "true".equals(text);
    }

    /** 按 {@code a.b[2].c} 形式的路径取值，取不到返回 null。 */
    public JsonNode at(String path) {
        if (path == null || path.isEmpty()) {
            return this;
        }
        JsonNode cur = this;
        for (String seg : Path.split(path)) {
            if (cur == null) {
                return null;
            }
            if (seg.startsWith("[") && seg.endsWith("]")) {
                int idx;
                try {
                    idx = Integer.parseInt(seg.substring(1, seg.length() - 1));
                } catch (NumberFormatException e) {
                    return null;
                }
                cur = cur.item(idx);
            } else {
                cur = cur.member(seg);
            }
        }
        return cur;
    }

    /** 遍历所有叶子节点，回调里给出「点分路径」与节点。 */
    public void walkLeaves(String prefix, java.util.function.BiConsumer<String, JsonNode> visitor) {
        if (!isContainer()) {
            visitor.accept(prefix, this);
            return;
        }
        for (int i = 0; i < children.size(); i++) {
            String childPath;
            if (kind == Kind.OBJECT) {
                childPath = prefix.isEmpty() ? keys.get(i) : prefix + "." + keys.get(i);
            } else {
                childPath = prefix + "[" + i + "]";
            }
            children.get(i).walkLeaves(childPath, visitor);
        }
    }

    @Override
    public String toString() {
        return kind + "(" + raw + ")";
    }
}
