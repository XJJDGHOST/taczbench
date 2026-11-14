package com.taczbench.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字段方案。决定界面上出现哪些滑条、叫什么名字、范围多少。
 * <p>
 * 内置一份针对 TACZ 1.20.1 枪械数据的方案；同时支持从 {@code config/taczbench/schema.json}
 * 读取覆盖版本——TACZ 以后加了新字段、或者你想加中英对照说明，都不用重新编译模组。
 * 方案里没写到的字段也不会丢：界面会自动把它们列在「其它字段」里。
 */
public final class Schema {

    public static final class Group {
        public final String id;
        public final String title;
        public final String appliesTo;
        public final List<FieldSpec> fields = new ArrayList<>();

        public Group(String id, String title, String appliesTo) {
            this.id = id;
            this.title = title;
            this.appliesTo = appliesTo;
        }

        Group add(FieldSpec spec) {
            fields.add(spec);
            return this;
        }

        public boolean matches(String kind) {
            return "*".equals(appliesTo) || appliesTo.equals(kind);
        }
    }

    private final List<Group> groups;

    private Schema(List<Group> groups) {
        this.groups = groups;
    }

    public List<Group> groups() { return groups; }

    public List<Group> groupsFor(String kind) {
        List<Group> out = new ArrayList<>();
        for (Group g : groups) {
            if (g.matches(kind)) {
                out.add(g);
            }
        }
        return out;
    }

    public FieldSpec find(String kind, String path) {
        for (Group g : groups) {
            if (!g.matches(kind)) {
                continue;
            }
            for (FieldSpec f : g.fields) {
                if (f.path().equals(path)) {
                    return f;
                }
            }
        }
        return null;
    }

    /** 方案里没有的字段，按文档里实际存在的叶子自动兜底。 */
    public List<String> unknownLeaves(String kind, JsonNode root) {
        List<String> out = new ArrayList<>();
        root.walkLeaves("", (path, node) -> {
            if (node.isContainer()) {
                return;
            }
            if (find(kind, path) == null) {
                out.add(path);
            }
        });
        return out;
    }

    // ------------------------------------------------------------ 默认方案

    /**
     * 内置方案。数值范围取自 TACZ 官方默认枪包的实际取值，留了合理余量；
     * 注释内容参考官方 ak47_data.json 里的中文说明。
     */
    public static Schema builtin() {
        List<Group> list = new ArrayList<>();

        Group bullet = new Group("bullet", "伤害与弹道", "guns");
        bullet.add(FieldSpec.num("bullet.damage", "基础伤害", 0, 500, 0.5, "点",
                "没有距离衰减表时用这个值；有表时改它会把整张表按比例一起改"));
        bullet.add(FieldSpec.integer("bullet.bullet_amount", "弹丸数量", 1, 64, "颗", "霰弹枪用，每发的伤害会被这个数平均分配"));
        bullet.add(FieldSpec.num("bullet.extra_damage.armor_ignore", "护甲穿透率", 0, 1, 0.05, "", "0 表示完全被护甲抵消，1 表示无视护甲"));
        bullet.add(FieldSpec.num("bullet.extra_damage.head_shot_multiplier", "爆头倍率", 1, 10, 0.1, "×", "命中头部时的伤害倍率"));
        bullet.add(FieldSpec.integer("bullet.pierce", "穿透数", 0, 20, "个", "能连续穿透的实体数量"));
        bullet.add(FieldSpec.num("bullet.speed", "弹速", 0, 2000, 5, "m/s", "游戏内实际弹速是这个值的两倍"));
        bullet.add(FieldSpec.num("bullet.life", "子弹寿命", 0, 10, 0.05, "秒", "飞多久后消失"));
        bullet.add(FieldSpec.num("bullet.gravity", "子弹重力", 0, 5, 0.01, "", "下坠速度，0 为平飞"));
        bullet.add(FieldSpec.num("bullet.friction", "空气阻力", 0, 1, 0.01, "", "越大减速越明显"));
        bullet.add(FieldSpec.num("bullet.knockback", "击退强度", 0, 10, 0.1, "", "命中实体时施加的击退"));
        bullet.add(FieldSpec.integer("bullet.tracer_count_interval", "曳光弹间隔", 0, 20, "发", "每几发出现一颗曳光弹，0 表示每发都是"));
        bullet.add(FieldSpec.bool("allow_ammo_unload", "允许快速卸弹", "能否用快捷键把弹匣里的子弹退出来"));
        list.add(bullet);

        // TACZ 的 DamageModifier 里：只要存在 damage_adjust，bullet.damage 就不参与伤害计算，
        // 实际每发伤害完全照这张「距离 → 伤害」表来。所以必须能改它。
        Group falloff = new Group("falloff", "距离衰减（这张表才是实际伤害）", "guns");
        falloff.add(FieldSpec.num("bullet.extra_damage.damage_adjust[0].damage", "近距离伤害", 0, 500, 0.5, "点",
                "第一档距离内的实际每发伤害，改「基础伤害」会按比例同步这里"));
        falloff.add(FieldSpec.num("bullet.extra_damage.damage_adjust[1].damage", "中距离伤害", 0, 500, 0.5, "点", "第二档距离"));
        falloff.add(FieldSpec.num("bullet.extra_damage.damage_adjust[2].damage", "远距离伤害", 0, 500, 0.5, "点", "第三档距离"));
        falloff.add(FieldSpec.num("bullet.extra_damage.damage_adjust[3].damage", "更远伤害", 0, 500, 0.5, "点", "第 4 档（大部分枪只有 3 档）"));
        list.add(falloff);

        Group fire = new Group("fire", "射速与动作", "guns");
        fire.add(FieldSpec.integer("rpm", "射速", 1, 1200, "发/分", "每分钟射出的弹药量，TACZ 上限 1200"));
        fire.add(FieldSpec.enumOf("bolt", "枪栓类型", "决定上膛和待击方式",
                "closed_bolt", "open_bolt", "manual_action"));
        fire.add(FieldSpec.integer("burst_data.count", "连发数", 0, 20, "发", "多连发模式每次扳机的发数"));
        fire.add(FieldSpec.integer("burst_data.bpm", "连发射速", 0, 1200, "发/分", "连发组内的射速"));
        fire.add(FieldSpec.num("burst_data.min_interval", "连发间隔", 0, 5, 0.05, "秒", "两组连发之间的停顿"));
        fire.add(FieldSpec.num("draw_time", "抬枪时间", 0, 5, 0.01, "秒", "切换到此枪后可开火所需的时间"));
        fire.add(FieldSpec.num("put_away_time", "收枪时间", 0, 5, 0.01, "秒", ""));
        fire.add(FieldSpec.num("aim_time", "开镜时间", 0, 5, 0.01, "秒", ""));
        fire.add(FieldSpec.num("sprint_time", "冲刺收枪时间", 0, 5, 0.01, "秒", ""));
        fire.add(FieldSpec.num("bolt_action_time", "拉栓时间", 0, 5, 0.01, "秒", "仅手动上膛的枪有效"));
        list.add(fire);

        Group accuracy = new Group("accuracy", "散布与后坐", "guns");
        accuracy.add(FieldSpec.num("inaccuracy.stand", "站立散布", 0, 30, 0.1, "", "数值越小越准"));
        accuracy.add(FieldSpec.num("inaccuracy.move", "移动散布", 0, 30, 0.1, "", ""));
        accuracy.add(FieldSpec.num("inaccuracy.sneak", "潜行散布", 0, 30, 0.1, "", ""));
        accuracy.add(FieldSpec.num("inaccuracy.lie", "趴下散布", 0, 30, 0.1, "", ""));
        accuracy.add(FieldSpec.num("inaccuracy.aim", "瞄准散布", 0, 30, 0.1, "", "开镜后的散布，通常明显小于腰射"));
        accuracy.add(FieldSpec.num("crawl_recoil_multiplier", "趴下后坐倍率", 0, 2, 0.05, "×", ""));
        accuracy.add(FieldSpec.num("hurt_bob_tweak_multiplier", "受伤晃动倍率", 0, 1, 0.01, "×", "1 为原版晃动，0 为完全不晃"));
        list.add(accuracy);

        Group ammo = new Group("ammo", "弹匣与换弹", "guns");
        ammo.add(FieldSpec.integer("ammo_amount", "弹匣容量", 1, 999, "发", ""));
        ammo.add(FieldSpec.integer("extended_mag_ammo_amount[0]", "扩容弹匣 I", 1, 999, "发", "扩容弹匣配件加成后的容量"));
        ammo.add(FieldSpec.integer("extended_mag_ammo_amount[1]", "扩容弹匣 II", 1, 999, "发", ""));
        ammo.add(FieldSpec.integer("extended_mag_ammo_amount[2]", "扩容弹匣 III", 1, 999, "发", ""));
        ammo.add(FieldSpec.enumOf("reload.type", "供弹方式", "",
                "magazine", "manual", "fuel", "inventory"));
        ammo.add(FieldSpec.bool("reload.infinite", "无限备弹", "开启后不需要消耗背包里的弹药"));
        ammo.add(FieldSpec.num("reload.feed.empty", "空仓换弹·上弹点", 0, 10, 0.05, "秒", "到这个时间点服务端就更新枪内子弹数"));
        ammo.add(FieldSpec.num("reload.feed.tactical", "战术换弹·上弹点", 0, 10, 0.05, "秒", ""));
        ammo.add(FieldSpec.num("reload.cooldown.empty", "空仓换弹·总时长", 0, 10, 0.05, "秒", "到这个时间点才能再次开火"));
        ammo.add(FieldSpec.num("reload.cooldown.tactical", "战术换弹·总时长", 0, 10, 0.05, "秒", ""));
        list.add(ammo);

        Group weight = new Group("weight", "重量与移速", "guns");
        weight.add(FieldSpec.num("weight", "重量", 0, 30, 0.1, "kg", "每 kg 约降低 1.5% 移速"));
        weight.add(FieldSpec.num("movement_speed.base", "移速修正·常态", -1, 1, 0.01, "", "最终乘算"));
        weight.add(FieldSpec.num("movement_speed.aim", "移速修正·瞄准", -1, 1, 0.01, "", ""));
        weight.add(FieldSpec.num("movement_speed.reload", "移速修正·换弹", -1, 1, 0.01, "", ""));
        list.add(weight);

        // 配件：官方把配件数值放在 data/attachments/<id>_data.json，
        // 不同配件字段差异很大，这里只收录公共的几个，其余自动列进「其它字段」。
        Group attachment = new Group("attachment", "配件属性", "attachments");
        attachment.add(FieldSpec.num("weight", "重量", 0, 20, 0.1, "kg", "会叠加到枪械总重"));
        attachment.add(FieldSpec.num("ads_addend", "开镜时间加值", -2, 2, 0.01, "秒", "负值让开镜更快"));
        attachment.add(FieldSpec.num("inaccuracy_addend", "散布加值", -30, 30, 0.1, "", "负值让枪更准"));
        attachment.add(FieldSpec.num("recoil_modifier.pitch", "垂直后坐修正", -2, 2, 0.05, "×", ""));
        attachment.add(FieldSpec.num("recoil_modifier.yaw", "水平后坐修正", -2, 2, 0.05, "×", ""));
        list.add(attachment);

        return new Schema(list);
    }

    // ------------------------------------------------------------ 读写

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"schemaVersion\": 1,\n");
        sb.append("  // 这是 TaCZ Bench 的字段方案：改 label 就改界面上的名字，改 min/max/step 就改滑条范围。\n");
        sb.append("  // 想要加 TACZ 新版本的字段，照着下面的格式加一条即可，不用重新编译模组。\n");
        sb.append("  \"groups\": [\n");
        for (int gi = 0; gi < groups.size(); gi++) {
            Group g = groups.get(gi);
            sb.append("    {\n");
            sb.append("      \"id\": ").append(JsonDoc.quote(g.id)).append(",\n");
            sb.append("      \"title\": ").append(JsonDoc.quote(g.title)).append(",\n");
            sb.append("      \"appliesTo\": ").append(JsonDoc.quote(g.appliesTo)).append(",\n");
            sb.append("      \"fields\": [\n");
            for (int fi = 0; fi < g.fields.size(); fi++) {
                g.fields.get(fi).appendJson(sb, "        ");
                sb.append(fi == g.fields.size() - 1 ? "\n" : ",\n");
            }
            sb.append("      ]\n");
            sb.append("    }");
            sb.append(gi == groups.size() - 1 ? "\n" : ",\n");
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    public static Schema parse(String json) {
        JsonNode root = JsonDoc.parse(json).root();
        List<Group> list = new ArrayList<>();
        JsonNode groupsNode = root.member("groups");
        if (groupsNode != null && groupsNode.isArray()) {
            for (JsonNode g : groupsNode.children()) {
                String id = g.member("id") == null ? "group" + list.size() : g.member("id").asString();
                String title = g.member("title") == null ? id : g.member("title").asString();
                String appliesTo = g.member("appliesTo") == null ? "*" : g.member("appliesTo").asString();
                Group group = new Group(id, title, appliesTo);
                JsonNode fields = g.member("fields");
                if (fields != null && fields.isArray()) {
                    for (JsonNode f : fields.children()) {
                        FieldSpec spec = FieldSpec.fromJson(f);
                        if (!spec.path().isEmpty()) {
                            group.fields.add(spec);
                        }
                    }
                }
                list.add(group);
            }
        }
        return new Schema(list);
    }

    /** 合并：用户方案里有的组覆盖内置，没有的保留内置。 */
    public Schema mergeOver(Schema fallback) {
        Map<String, Group> merged = new LinkedHashMap<>();
        for (Group g : fallback.groups) {
            merged.put(g.id, g);
        }
        for (Group g : groups) {
            merged.put(g.id, g);
        }
        return new Schema(new ArrayList<>(merged.values()));
    }
}
