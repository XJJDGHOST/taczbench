package com.taczbench.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 支持注释的 JSON（JSON5 宽容子集）文档，可在保留原始排版的前提下精准改写单个值。
 * <p>
 * 只做三件事：解析成带下标的树、按路径改值、把改好的文本吐出来。
 * 不依赖 Gson，方便脱离 Minecraft 单独跑测试。
 */
public final class JsonDoc {

    private final String src;
    private final JsonNode root;
    private int pos;

    private JsonDoc(String src, JsonNode root) {
        this.src = src;
        this.root = root;
    }

    // ---------------------------------------------------------------- 解析

    public static JsonDoc parse(String text) {
        JsonDoc doc = new JsonDoc(text == null ? "" : text, null);
        doc.pos = 0;
        doc.skipWs();
        if (doc.pos >= doc.src.length()) {
            throw new JsonSyntaxError("文件是空的", text, 0);
        }
        JsonNode root = doc.parseValue(0);
        doc.skipWs();
        if (doc.pos < doc.src.length()) {
            throw doc.error("JSON 结束后还有多余内容");
        }
        return new JsonDoc(text, root);
    }

    public String source() { return src; }

    public JsonNode root() { return root; }

    public JsonNode at(String path) { return root.at(path); }

    private JsonNode parseValue(int depth) {
        if (depth > 64) {
            throw error("JSON 嵌套层级过深");
        }
        skipWs();
        if (pos >= src.length()) {
            throw error("意外的文件结尾");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return parseObject(depth);
            case '[':
                return parseArray(depth);
            case '"':
                return parseStringNode();
            default:
                return parseScalar();
        }
    }

    private JsonNode parseObject(int depth) {
        int start = pos;
        JsonNode node = new JsonNode(JsonNode.Kind.OBJECT, start);
        pos++; // {
        skipWs();
        if (peek() == '}') {
            pos++;
            node.end = pos;
            node.raw = src.substring(start, pos);
            return node;
        }
        while (true) {
            skipWs();
            if (peek() != '"') {
                throw error("对象的键必须是字符串");
            }
            JsonNode keyNode = parseStringNode();
            skipWs();
            if (peek() != ':') {
                throw error("键后面缺少冒号");
            }
            pos++;
            JsonNode value = parseValue(depth + 1);
            node.keys.add(keyNode.asString());
            node.children.add(value);
            node.members.put(keyNode.asString(), value);
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                break;
            }
            throw error("对象里应该是 ',' 或 '}'");
        }
        node.end = pos;
        node.raw = src.substring(start, pos);
        return node;
    }

    private JsonNode parseArray(int depth) {
        int start = pos;
        JsonNode node = new JsonNode(JsonNode.Kind.ARRAY, start);
        pos++; // [
        skipWs();
        if (peek() == ']') {
            pos++;
            node.end = pos;
            node.raw = src.substring(start, pos);
            return node;
        }
        while (true) {
            JsonNode value = parseValue(depth + 1);
            node.children.add(value);
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                break;
            }
            throw error("数组里应该是 ',' 或 ']'");
        }
        node.end = pos;
        node.raw = src.substring(start, pos);
        return node;
    }

    private JsonNode parseStringNode() {
        int start = pos;
        pos++; // "
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\\') {
                pos++;
                if (pos >= src.length()) {
                    throw error("字符串里的转义不完整");
                }
                char e = src.charAt(pos);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 >= src.length()) {
                            throw error("\\u 转义不完整");
                        }
                        String hex = src.substring(pos + 1, pos + 5);
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException ex) {
                            throw error("\\u 转义不是合法十六进制: " + hex);
                        }
                        pos += 4;
                    }
                    default -> sb.append(e);
                }
                pos++;
            } else if (c == '"') {
                pos++;
                JsonNode node = new JsonNode(JsonNode.Kind.STRING, start);
                node.end = pos;
                node.raw = src.substring(start, pos);
                node.text = sb.toString();
                return node;
            } else {
                sb.append(c);
                pos++;
            }
        }
        throw error("字符串没有闭合");
    }

    private JsonNode parseScalar() {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) {
                break;
            }
            pos++;
        }
        String raw = src.substring(start, pos);
        if (raw.isEmpty()) {
            throw error("这里缺少一个值");
        }
        JsonNode.Kind kind;
        if ("true".equals(raw) || "false".equals(raw)) {
            kind = JsonNode.Kind.BOOLEAN;
        } else if ("null".equals(raw)) {
            kind = JsonNode.Kind.NULL;
        } else {
            kind = JsonNode.Kind.NUMBER;
            try {
                Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                throw error("无法识别的值: " + raw);
            }
        }
        JsonNode node = new JsonNode(kind, start);
        node.end = pos;
        node.raw = raw;
        node.text = raw;
        return node;
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '/') {
                pos += 2;
                while (pos < src.length() && src.charAt(pos) != '\n') {
                    pos++;
                }
            } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '*') {
                pos += 2;
                while (pos + 1 < src.length() && !(src.charAt(pos) == '*' && src.charAt(pos + 1) == '/')) {
                    pos++;
                }
                pos = Math.min(src.length(), pos + 2);
            } else {
                return;
            }
        }
    }

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private JsonSyntaxError error(String message) {
        return new JsonSyntaxError(message, src, pos);
    }

    // ---------------------------------------------------------------- 改写

    /** 一次文本替换：把 {@code [start, end)} 换成 {@code text}。 */
    public record Edit(int start, int end, String text) {
    }

    /**
     * 生成把 {@code path} 改成 {@code literal} 所需的编辑操作。
     * 路径不存在时会在其父对象里插入（保持缩进风格）。
     */
    public List<Edit> editsFor(Map<String, String> values) {
        List<Edit> edits = new ArrayList<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            String path = e.getKey();
            String literal = e.getValue();
            JsonNode node = root.at(path);
            if (node != null) {
                if (!node.raw.equals(literal)) {
                    edits.add(new Edit(node.start, node.end, literal));
                }
                continue;
            }
            Edit inserted = insertEdit(path, literal);
            if (inserted != null) {
                edits.add(inserted);
            }
        }
        return edits;
    }

    /** 取值不存在时，在父对象末尾插入一行。 */
    private Edit insertEdit(String path, String literal) {
        String parentPath = Path.parentOf(path);
        String last = Path.lastOf(path);
        JsonNode parent = parentPath.isEmpty() ? root : root.at(parentPath);
        if (parent == null || !parent.isObject() || last.isEmpty() || last.startsWith("[")) {
            return null;
        }
        if (parent.has(last)) {
            return null;
        }
        int closeBrace = parent.end - 1;
        String closingIndent = lineIndent(closeBrace);
        String memberIndent = closingIndent + "  ";
        String insertion;
        int at;
        if (parent.size() == 0) {
            at = closeBrace;
            insertion = System.lineSeparator() + memberIndent + quote(last) + ": " + literal
                    + System.lineSeparator() + closingIndent;
        } else {
            JsonNode lastChild = parent.children.get(parent.size() - 1);
            at = lastChild.end;
            insertion = "," + System.lineSeparator() + memberIndent + quote(last) + ": " + literal;
        }
        return new Edit(at, at, insertion);
    }

    private String lineIndent(int offset) {
        int lineStart = src.lastIndexOf('\n', Math.max(0, offset - 1));
        if (lineStart < 0) {
            return "";
        }
        int i = lineStart + 1;
        StringBuilder sb = new StringBuilder();
        while (i < src.length() && (src.charAt(i) == ' ' || src.charAt(i) == '\t')) {
            sb.append(src.charAt(i));
            i++;
        }
        return sb.toString();
    }

    /** 应用一批编辑，返回新文本。编辑区间重叠时会丢弃被包住的那条。 */
    public String applyEdits(List<Edit> edits) {
        List<Edit> sorted = new ArrayList<>(edits);
        sorted.sort(Comparator.comparingInt(Edit::start).thenComparingInt(Edit::end));
        List<Edit> clean = new ArrayList<>();
        int lastEnd = -1;
        for (Edit e : sorted) {
            if (e.start() < lastEnd) {
                continue;
            }
            clean.add(e);
            lastEnd = e.end();
        }
        StringBuilder sb = new StringBuilder(src);
        for (int i = clean.size() - 1; i >= 0; i--) {
            Edit e = clean.get(i);
            sb.replace(e.start(), e.end(), e.text());
        }
        return sb.toString();
    }

    /** 一步到位：把值改掉，返回新文本。 */
    public String render(Map<String, String> values) {
        return applyEdits(editsFor(values));
    }

    /** 这个路径能不能写：要么本身存在，要么它的父对象存在（可以插入）。 */
    public boolean canWrite(String path) {
        if (root.at(path) != null) {
            return true;
        }
        String parentPath = Path.parentOf(path);
        JsonNode parent = parentPath.isEmpty() ? root : root.at(parentPath);
        String last = Path.lastOf(path);
        return parent != null && parent.isObject() && !last.isEmpty() && !last.startsWith("[") && !parent.has(last);
    }

    // ---------------------------------------------------------------- 字面量

    public static String numberLiteral(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "0";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1.0E15D) {
            return Long.toString((long) v);
        }
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    public static String boolLiteral(boolean b) {
        return b ? "true" : "false";
    }

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** 解析出错时带着行号抛出来，方便界面直接提示「第几行错了」。 */
    public static final class JsonSyntaxError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final int line;
        private final int column;

        JsonSyntaxError(String message, String src, int offset) {
            super(message + "（第 " + lineOf(src, offset) + " 行，第 " + columnOf(src, offset) + " 列）");
            this.line = lineOf(src, offset);
            this.column = columnOf(src, offset);
        }

        public int line() { return line; }

        public int column() { return column; }

        private static int lineOf(String src, int offset) {
            int line = 1;
            for (int i = 0; i < Math.min(offset, src.length()); i++) {
                if (src.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        }

        private static int columnOf(String src, int offset) {
            int start = src.lastIndexOf('\n', Math.max(0, offset - 1));
            return offset - start;
        }
    }
}
