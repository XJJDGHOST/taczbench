package com.taczbench.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一个可调字段的描述：路径、显示名、类型、范围、单位、说明。 */
public final class FieldSpec {

    public enum Kind { NUMBER, INT, BOOL, ENUM, TEXT }

    private final String path;
    private final String label;
    private final String desc;
    private final Kind kind;
    private final double min;
    private final double max;
    private final double step;
    private final String unit;
    private final List<String> options;

    public FieldSpec(String path, String label, Kind kind, double min, double max, double step,
                     String unit, String desc, List<String> options) {
        this.path = path;
        this.label = label;
        this.kind = kind;
        this.min = min;
        this.max = max;
        this.step = step <= 0 ? (kind == Kind.INT ? 1 : 0.1) : step;
        this.unit = unit == null ? "" : unit;
        this.desc = desc == null ? "" : desc;
        this.options = options == null ? Collections.emptyList() : List.copyOf(options);
    }

    public String path() { return path; }

    public String label() { return label; }

    public String desc() { return desc; }

    public Kind kind() { return kind; }

    public double min() { return min; }

    public double max() { return max; }

    public double step() { return step; }

    public String unit() { return unit; }

    public List<String> options() { return options; }

    public boolean isNumeric() { return kind == Kind.NUMBER || kind == Kind.INT; }

    public double clamp(double v) {
        double r = Math.max(min, Math.min(max, v));
        if (kind == Kind.INT) {
            return Math.rint(r);
        }
        // 按步长吸附，避免出现 0.30000000000000004
        double stepped = Math.round((r - min) / step) * step + min;
        return Math.max(min, Math.min(max, stepped));
    }

    /** 内置字段的简写构造。 */
    public static FieldSpec num(String path, String label, double min, double max, double step, String unit, String desc) {
        return new FieldSpec(path, label, Kind.NUMBER, min, max, step, unit, desc, null);
    }

    public static FieldSpec integer(String path, String label, double min, double max, String unit, String desc) {
        return new FieldSpec(path, label, Kind.INT, min, max, 1, unit, desc, null);
    }

    public static FieldSpec bool(String path, String label, String desc) {
        return new FieldSpec(path, label, Kind.BOOL, 0, 1, 1, "", desc, null);
    }

    public static FieldSpec enumOf(String path, String label, String desc, String... options) {
        List<String> opts = new ArrayList<>(List.of(options));
        return new FieldSpec(path, label, Kind.ENUM, 0, opts.size() - 1, 1, "", desc, opts);
    }

    // ------------------------------------------------------------ 序列化（写回配置文件用）

    public void appendJson(StringBuilder sb, String indent) {
        sb.append(indent).append("{\n");
        sb.append(indent).append("  \"path\": ").append(JsonDoc.quote(path)).append(",\n");
        sb.append(indent).append("  \"label\": ").append(JsonDoc.quote(label)).append(",\n");
        sb.append(indent).append("  \"type\": ").append(JsonDoc.quote(kind.name().toLowerCase())).append(",\n");
        if (kind == Kind.NUMBER || kind == Kind.INT) {
            sb.append(indent).append("  \"min\": ").append(JsonDoc.numberLiteral(min)).append(",\n");
            sb.append(indent).append("  \"max\": ").append(JsonDoc.numberLiteral(max)).append(",\n");
            sb.append(indent).append("  \"step\": ").append(JsonDoc.numberLiteral(step)).append(",\n");
        }
        if (!unit.isEmpty()) {
            sb.append(indent).append("  \"unit\": ").append(JsonDoc.quote(unit)).append(",\n");
        }
        if (kind == Kind.ENUM) {
            sb.append(indent).append("  \"options\": [");
            for (int i = 0; i < options.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(JsonDoc.quote(options.get(i)));
            }
            sb.append("],\n");
        }
        sb.append(indent).append("  \"desc\": ").append(JsonDoc.quote(desc)).append("\n");
        sb.append(indent).append("}");
    }

    static FieldSpec fromJson(JsonNode n) {
        String path = str(n.member("path"), "");
        String label = str(n.member("label"), path);
        String desc = str(n.member("desc"), "");
        String unit = str(n.member("unit"), "");
        String type = str(n.member("type"), "number").toLowerCase();
        Kind kind = switch (type) {
            case "int", "integer" -> Kind.INT;
            case "bool", "boolean" -> Kind.BOOL;
            case "enum" -> Kind.ENUM;
            case "text", "string" -> Kind.TEXT;
            default -> Kind.NUMBER;
        };
        double min = n.member("min") == null ? 0 : n.member("min").asDouble();
        double max = n.member("max") == null ? 100 : n.member("max").asDouble();
        double step = n.member("step") == null ? (kind == Kind.INT ? 1 : 0.1) : n.member("step").asDouble();
        List<String> options = new ArrayList<>();
        JsonNode opts = n.member("options");
        if (opts != null && opts.isArray()) {
            for (JsonNode o : opts.children()) {
                options.add(o.asString());
            }
        }
        return new FieldSpec(path, label, kind, min, max, step, unit, desc, options);
    }

    private static String str(JsonNode n, String def) {
        return n == null ? def : n.asString();
    }
}
