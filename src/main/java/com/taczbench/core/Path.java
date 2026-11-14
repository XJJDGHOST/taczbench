package com.taczbench.core;

import java.util.ArrayList;
import java.util.List;

/** 点分路径工具：{@code bullet.extra_damage.armor_ignore}、{@code extended_mag_ammo_amount[0]}。 */
public final class Path {

    private Path() {
    }

    /** 把 {@code a.b[2].c} 拆成 {@code [a, b, [2], c]}。 */
    public static List<String> split(String path) {
        List<String> out = new ArrayList<>();
        if (path == null || path.isEmpty()) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.') {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else if (c == '[') {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                int close = path.indexOf(']', i);
                if (close < 0) {
                    throw new IllegalArgumentException("路径缺少右括号: " + path);
                }
                out.add(path.substring(i, close + 1));
                i = close;
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    public static String join(List<String> segs) {
        StringBuilder sb = new StringBuilder();
        for (String s : segs) {
            if (s.startsWith("[")) {
                sb.append(s);
            } else {
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(s);
            }
        }
        return sb.toString();
    }

    public static String parentOf(String path) {
        List<String> segs = split(path);
        if (segs.isEmpty()) {
            return "";
        }
        segs.remove(segs.size() - 1);
        return join(segs);
    }

    public static String lastOf(String path) {
        List<String> segs = split(path);
        return segs.isEmpty() ? "" : segs.get(segs.size() - 1);
    }
}
