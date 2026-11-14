package com.taczbench.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.Font;

/** 界面配色和几个绘制小工具。整套界面不依赖任何贴图，纯色块 + 细边框，缩放小也不会糊。 */
public final class Ui {

    public static final int PANEL = 0xF016191D;
    public static final int PANEL_2 = 0xF01D2126;
    public static final int ROW = 0x00FFFFFF;
    public static final int ROW_HOVER = 0x14FFFFFF;
    public static final int ROW_SELECTED = 0x26E8A33D;
    public static final int BORDER = 0xFF31363D;
    public static final int BORDER_SOFT = 0xFF262A30;
    public static final int TEXT = 0xFFE8EAED;
    public static final int TEXT_DIM = 0xFF9AA0A6;
    public static final int TEXT_FAINT = 0xFF6B7178;
    public static final int ACCENT = 0xFFE8A33D;
    public static final int ACCENT_SOFT = 0x55E8A33D;
    public static final int DANGER = 0xFFE06C6C;
    public static final int OK = 0xFF77C795;
    public static final int SLOT = 0xFF2A2F36;
    public static final int SLOT_FILL = 0xFFE8A33D;

    private Ui() {
    }

    /** 面板：底色 + 1px 边框。 */
    public static void panel(GuiGraphics g, int x0, int y0, int x1, int y1, int fill, int border) {
        g.fill(x0, y0, x1, y1, fill);
        g.fill(x0, y0, x1, y0 + 1, border);
        g.fill(x0, y1 - 1, x1, y1, border);
        g.fill(x0, y0, x0 + 1, y1, border);
        g.fill(x1 - 1, y0, x1, y1, border);
    }

    public static void hLine(GuiGraphics g, int x0, int x1, int y, int color) {
        g.fill(x0, y, x1, y + 1, color);
    }

    public static void text(GuiGraphics g, Font font, String s, int x, int y, int color) {
        g.drawString(font, s, x, y, color, false);
    }

    public static void textShadow(GuiGraphics g, Font font, String s, int x, int y, int color) {
        g.drawString(font, s, x, y, color, true);
    }

    /** 左侧带色条的小标签，比如「步枪」「tacz」。 */
    public static void tag(GuiGraphics g, Font font, String label, int x, int y, int color) {
        int w = font.width(label) + 6;
        g.fill(x, y, x + w, y + 10, (color & 0x00FFFFFF) | 0x33000000);
        g.fill(x, y, x + 2, y + 10, color);
        g.drawString(font, label, x + 4, y + 1, color, false);
    }

    /** 细进度条，用作滑条底槽。 */
    public static void track(GuiGraphics g, int x0, int y0, int x1, int y1, double progress, boolean active) {
        g.fill(x0, y0, x1, y1, SLOT);
        int w = Math.max(0, (int) Math.round((x1 - x0) * Math.max(0, Math.min(1, progress))));
        if (w > 0) {
            g.fill(x0, y0, x0 + w, y1, active ? SLOT_FILL : (SLOT_FILL & 0x00FFFFFF) | 0x66000000);
        }
        // 滑块
        int kx = Math.max(x0, Math.min(x1 - 2, x0 + w - 1));
        g.fill(kx, y0 - 1, kx + 2, y1 + 1, active ? 0xFFFFFFFF : 0xFFB9BEC4);
    }

    public static void scissorOn(GuiGraphics g, int x0, int y0, int x1, int y1) {
        g.enableScissor(x0, y0, x1, y1);
        RenderSystem.enableBlend();
    }

    public static void scissorOff(GuiGraphics g) {
        g.disableScissor();
    }

    public static String trim(Font font, String s, int maxWidth) {
        if (font.width(s) <= maxWidth) {
            return s;
        }
        String ellipsis = "…";
        int budget = maxWidth - font.width(ellipsis);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (font.width(sb.toString() + s.charAt(i)) > budget) {
                break;
            }
            sb.append(s.charAt(i));
        }
        return sb + ellipsis;
    }

    public static Component tr(String key, Object... args) {
        return Component.translatable(key, args);
    }
}
