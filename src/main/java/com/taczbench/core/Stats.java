package com.taczbench.core;

/** 由枪械数据现算出来的实战指标：单发伤害、DPS、打空弹匣耗时、击杀时间。 */
public final class Stats {

    public final double damagePerShot;
    public final double damagePerPellet;
    public final double headshotDamage;
    public final double dps;
    public final double sustainedDps;
    public final double magDumpSeconds;
    public final double ttkSeconds;
    public final int shotsToKill;
    public final int rpm;

    private Stats(double damagePerShot, double damagePerPellet, double headshotDamage, double dps, double sustainedDps,
                  double magDumpSeconds, double ttkSeconds, int shotsToKill, int rpm) {
        this.damagePerShot = damagePerShot;
        this.damagePerPellet = damagePerPellet;
        this.headshotDamage = headshotDamage;
        this.dps = dps;
        this.sustainedDps = sustainedDps;
        this.magDumpSeconds = magDumpSeconds;
        this.ttkSeconds = ttkSeconds;
        this.shotsToKill = shotsToKill;
        this.rpm = rpm;
    }

    /** @param targetHealth 目标血量，MC 玩家是 20。 */
    public static Stats compute(JsonNode root, double targetHealth) {
        // TACZ：只要有距离衰减表，bullet.damage 就不参与计算，实际伤害完全取自这张表
        double base = number(root, "bullet.damage", 0);
        JsonNode table = root.at("bullet.extra_damage.damage_adjust");
        if (table != null && table.isArray() && table.size() > 0) {
            JsonNode first = table.item(0).member("damage");
            if (first != null) {
                base = first.asDouble();
            }
        }
        int pellets = Math.max(1, (int) Math.round(number(root, "bullet.bullet_amount", 1)));
        // TACZ 的 EntityKineticBullet: damageModifier = 1f / bulletCount
        // 也就是说表里的伤害是「一次射击的总量」，每颗弹丸只有 1/弹丸数
        double perShot = base;
        double perPellet = pellets > 0 ? base / pellets : base;
        double headMul = number(root, "bullet.extra_damage.head_shot_multiplier", 1);
        int rpm = Math.max(0, (int) Math.round(number(root, "rpm", 0)));
        int mag = Math.max(0, (int) Math.round(number(root, "ammo_amount", 0)));
        double reload = number(root, "reload.cooldown.empty", 0);

        double dps = rpm <= 0 ? 0 : perShot * rpm / 60.0D;
        double dump = rpm <= 0 ? 0 : mag / (rpm / 60.0D);
        double sustained = dump + reload <= 0 ? 0 : perShot * mag / (dump + reload);

        int shots = perShot <= 0 ? 0 : (int) Math.ceil(targetHealth / perShot);
        double ttk = (shots <= 1 || rpm <= 0) ? 0 : (shots - 1) * 60.0D / rpm;

        return new Stats(perShot, perPellet, perShot * headMul, dps, sustained, dump, ttk, shots, rpm);
    }

    public static Stats compute(JsonNode root) {
        return compute(root, 20.0D);
    }

    private static double number(JsonNode root, String path, double fallback) {
        JsonNode n = root.at(path);
        return n == null ? fallback : n.asDouble();
    }

    @Override
    public String toString() {
        return String.format("单发 %.1f / DPS %.1f / 打空 %.2fs / TTK %.2fs", damagePerShot, dps, magDumpSeconds, ttkSeconds);
    }
}
