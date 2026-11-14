package com.taczbench.core;

import java.util.Locale;

/** 一个枪包里的一个数据文件（枪械或配件）。 */
public final class GunEntry {

    public final String kind;        // guns / attachments
    public final String namespace;   // 命名空间，如 tacz
    public final String id;          // 文件基名，如 ak47_data
    public final String entryPath;   // 包内路径 data/<ns>/data/guns/ak47_data.json
    public final String sourceLabel; // 来源说明，如 "tacz/tacz_default_gun.zip"
    public final String nameKey;     // 本地化键，如 tacz.gun.ak47.name
    public final String type;        // rifle / pistol / ...
    public final String rawText;     // 原始文本（含注释）
    public final java.nio.file.Path packPath;  // 枪包在磁盘上的位置（目录或 zip）
    public final String packKind;    // dir / zip

    private JsonDoc doc;
    private JsonDoc original;

    public GunEntry(String kind, String namespace, String id, String entryPath, String sourceLabel,
                    String nameKey, String type, String rawText,
                    java.nio.file.Path packPath, String packKind) {
        this.kind = kind;
        this.namespace = namespace;
        this.id = id;
        this.entryPath = entryPath;
        this.sourceLabel = sourceLabel;
        this.nameKey = nameKey;
        this.type = type == null ? "" : type;
        this.rawText = rawText;
        this.packPath = packPath;
        this.packKind = packKind;
    }

    public String key() {
        return kind + "|" + namespace + "|" + id;
    }

    /** 真正给人看的名字：优先本地化键，没有就用 id。 */
    public String fallbackName() {
        if (nameKey != null && !nameKey.isBlank()) {
            return nameKey;
        }
        String base = id.endsWith("_data") ? id.substring(0, id.length() - 5) : id;
        return base;
    }

    public String idWithoutSuffix() {
        return id.endsWith("_data") ? id.substring(0, id.length() - 5) : id;
    }

    /** 惰性解析：只有真的要显示/编辑时才解析。 */
    public JsonDoc doc() {
        if (doc == null) {
            doc = JsonDoc.parse(rawText);
        }
        return doc;
    }

    public JsonDoc originalDoc() {
        if (original == null) {
            original = JsonDoc.parse(rawText);
        }
        return original;
    }

    public boolean isGun() {
        return "guns".equals(kind);
    }

    /** 排序用：先按类别，再按名字。 */
    public String sortKey() {
        return typeOrder(type) + "|" + fallbackName().toLowerCase(Locale.ROOT);
    }

    private static int typeOrder(String type) {
        return switch (type == null ? "" : type) {
            case "rifle" -> 1;
            case "sniper" -> 2;
            case "shotgun" -> 3;
            case "smg" -> 4;
            case "pistol" -> 5;
            case "mg" -> 6;
            case "rpg" -> 7;
            case "grenade" -> 8;
            default -> 9;
        };
    }

    @Override
    public String toString() {
        return namespace + ":" + id + " (" + type + ")";
    }
}
