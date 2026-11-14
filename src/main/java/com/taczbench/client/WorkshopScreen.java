package com.taczbench.client;

import com.taczbench.TaczBench;
import com.taczbench.WorkshopService;
import com.taczbench.core.FieldSpec;
import com.taczbench.core.GunEntry;
import com.taczbench.core.JsonDoc;
import com.taczbench.core.JsonNode;
import com.taczbench.core.Schema;
import com.taczbench.core.Stats;
import com.taczbench.core.WorkshopRepository;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调校台主界面。
 * <p>
 * 左：枪包里的枪械列表（可搜索、分「枪械 / 配件」两栏）；中：分组好的数值滑条 + 实时指标；
 * 右：应用 / 撤销 / 还原 / 重扫。
 * <p>
 * 所有改动先只存在内存里，点「应用并重载」才写进存档的覆盖数据包——所以随便乱拖也不会弄坏枪包。
 */
public class WorkshopScreen extends Screen {

    /** 跨界面保留编辑状态，免得关一次界面就白改。key = 条目 key，value = 路径→字面量。 */
    private static final Map<String, Map<String, String>> EDITS = new LinkedHashMap<>();
    private static String lastKind = WorkshopRepository.KIND_GUNS;
    private static String lastSelectedKey;
    private static List<String> appliedCache = List.of();
    /** 自上次「应用」之后有没有新的改动。 */
    private static boolean pendingApply;

    private WorkshopRepository repo;
    private final Schema schema;

    private String kind;
    private String query = "";
    private List<GunEntry> filtered = new ArrayList<>();
    private GunEntry selected;
    private Map<String, String> edits = new LinkedHashMap<>();
    private String docError;

    private EditBox searchBox;
    private EditBox valueBox;
    private String editingPath;
    private boolean editingText;

    private final List<Hit> hits = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final Map<String, ItemStack> iconCache = new HashMap<>();
    private int fieldContentHeight;

    private int listScroll;
    private int fieldScroll;
    private boolean dragging;
    private String dragPath;

    private JsonNode effectiveCache;
    private Stats statsCache;
    private boolean cacheDirty = true;

    private String status = "";
    private int statusColor = Ui.TEXT_DIM;
    private long statusAt;

    // 布局
    private int px0, py0, px1, py1;
    private int listX0, listY0, listX1, listY1;
    private int fieldX0, fieldY0, fieldX1, fieldY1;
    private int sideX0, sideY0, sideX1, sideY1;
    private int tabY;
    private int listTop;

    public WorkshopScreen() {
        super(Component.translatable("taczbench.title"));
        this.repo = WorkshopService.repository();
        this.schema = WorkshopService.schema();
        this.kind = lastKind;
        refreshApplied();
    }

    // ================================================================ 生命周期

    @Override
    protected void init() {
        layout();
        searchBox = new EditBox(this.font, listX0 + 20, listY0 + 8, listX1 - listX0 - 28, 12,
                Component.translatable("taczbench.search"));
        searchBox.setBordered(false);
        searchBox.setMaxLength(48);
        searchBox.setValue(query);
        searchBox.setResponder(s -> {
            query = s;
            refreshList();
        });
        searchBox.setTextColor(Ui.TEXT);

        refreshList();
        if (lastSelectedKey != null) {
            for (GunEntry e : filtered) {
                if (e.key().equals(lastSelectedKey)) {
                    select(e);
                    break;
                }
            }
        }
        if (selected == null && !filtered.isEmpty()) {
            select(filtered.get(0));
        }
    }

    @Override
    public void tick() {
        if (searchBox != null) {
            searchBox.tick();
        }
        if (valueBox != null) {
            valueBox.tick();
        }
    }

    /** 不能暂停：应用后要靠服务端线程把数据包重载完。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ================================================================ 布局

    private void layout() {
        int pad = 8;
        px0 = pad;
        py0 = pad;
        px1 = this.width - pad;
        py1 = this.height - pad;

        int listWidth = Math.max(154, Math.min(212, this.width / 6));
        int sideWidth = Math.max(132, Math.min(158, this.width / 8));

        listX0 = px0 + 6;
        listY0 = py0 + 28;
        listX1 = px0 + listWidth;
        listY1 = py1 - 6;

        sideX1 = px1 - 6;
        sideX0 = sideX1 - sideWidth;
        sideY0 = py0 + 28;
        sideY1 = py1 - 6;

        fieldX0 = listX1 + 10;
        fieldX1 = sideX0 - 10;
        fieldY0 = py0 + 76;
        fieldY1 = py1 - 24;

        tabY = listY0 + 22;
        listTop = tabY + 16;
    }

    // ================================================================ 数据

    private void refreshList() {
        filtered = repo.search(kind, query);
        if (selected != null && !filtered.contains(selected)) {
            selected = null;
        }
        fieldScroll = 0;
        rebuildRows();
    }

    private void select(GunEntry entry) {
        selected = entry;
        lastSelectedKey = entry.key();
        edits = EDITS.computeIfAbsent(entry.key(), k -> new LinkedHashMap<>());
        fieldScroll = 0;
        docError = null;
        cacheDirty = true;
        try {
            entry.doc();
        } catch (RuntimeException e) {
            docError = e.getMessage();
        }
        rebuildRows();
    }

    private void rebuildRows() {
        rows.clear();
        fieldContentHeight = 0;
        if (selected == null || docError != null) {
            return;
        }
        JsonNode root = selected.doc().root();
        for (Schema.Group group : schema.groupsFor(kindOf(selected))) {
            if (group.fields.isEmpty()) {
                continue;
            }
            rows.add(new Row(group.title, null, null));
            for (FieldSpec spec : group.fields) {
                rows.add(new Row(null, spec, spec.path()));
            }
        }
        List<String> others = schema.unknownLeaves(kindOf(selected), root);
        if (!others.isEmpty()) {
            rows.add(new Row("其它字段 · 方案未收录，按实际类型自动识别", null, null));
            for (String path : others) {
                rows.add(new Row(null, null, path));
            }
        }
        int h = 0;
        for (Row r : rows) {
            h += r.header() != null ? 20 : 18;
        }
        fieldContentHeight = h;
    }

    private String kindOf(GunEntry entry) {
        return WorkshopRepository.KIND_ATTACHMENTS.equals(entry.kind)
                ? WorkshopRepository.KIND_ATTACHMENTS : WorkshopRepository.KIND_GUNS;
    }

    private void refreshApplied() {
        try {
            appliedCache = WorkshopService.appliedFiles();
        } catch (RuntimeException e) {
            appliedCache = List.of();
        }
    }

    // ================================================================ 取值 / 改值

    private String originalRaw(String path) {
        if (selected == null) {
            return null;
        }
        JsonNode n = selected.doc().at(path);
        return n == null ? null : n.raw();
    }

    private JsonNode node(String path) {
        return selected == null ? null : selected.doc().at(path);
    }

    private String literal(String path) {
        String v = edits.get(path);
        return v != null ? v : originalRaw(path);
    }

    private double number(String path, double fallback) {
        String lit = literal(path);
        if (lit == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(lit);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean bool(String path) {
        return "true".equals(literal(path));
    }

    private String asText(String path) {
        String lit = literal(path);
        if (lit == null) {
            return "";
        }
        try {
            return JsonDoc.parse("{\"v\": " + lit + "}").at("v").asString();
        } catch (RuntimeException e) {
            return lit;
        }
    }

    private boolean dirty(String path) {
        return edits.containsKey(path);
    }

    private void setLiteral(String path, String lit) {
        if (lit == null || selected == null) {
            return;
        }
        if (!selected.originalDoc().canWrite(path)) {
            setStatus("写不了 " + path + "：上级字段不存在", Ui.DANGER);
            return;
        }
        String orig = originalRaw(path);
        if (lit.equals(orig)) {
            edits.remove(path);
        } else {
            edits.put(path, lit);
            pendingApply = true;
        }
        cacheDirty = true;
    }

    private void setNumber(String path, double v) {
        setLiteral(path, JsonDoc.numberLiteral(v));
        if ("bullet.damage".equals(path)) {
            scaleFalloff(v);
        }
    }

    /**
     * TACZ 只要存在 damage_adjust，实际伤害就完全取自那张表。
     * 所以改「基础伤害」时，把整张表按同比例一起改，玩家看到的效果才对得上。
     */
    private void scaleFalloff(double newBase) {
        JsonNode table = node("bullet.extra_damage.damage_adjust");
        if (table == null || !table.isArray() || table.size() == 0) {
            return;
        }
        JsonNode first = table.item(0).member("damage");
        if (first == null || first.asDouble() <= 0) {
            return;
        }
        double base = first.asDouble();
        for (int i = 0; i < table.size(); i++) {
            JsonNode d = table.item(i).member("damage");
            if (d == null) {
                continue;
            }
            double v = newBase * (d.asDouble() / base);
            edits.put("bullet.extra_damage.damage_adjust[" + i + "].damage",
                    JsonDoc.numberLiteral(Math.round(v * 100.0) / 100.0));
        }
        cacheDirty = true;
    }

    private void reset(String path) {
        if (edits.remove(path) != null) {
            pendingApply = true;
        }
        if ("bullet.damage".equals(path)) {
            for (String key : new ArrayList<>(edits.keySet())) {
                if (key.startsWith("bullet.extra_damage.damage_adjust[")) {
                    edits.remove(key);
                }
            }
        }
        cacheDirty = true;
    }

    // ================================================================ 绘制

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        hits.clear();
        g.fill(0, 0, this.width, this.height, 0xD208090B);
        Ui.panel(g, px0, py0, px1, py1, Ui.PANEL, Ui.BORDER);

        drawTitleBar(g);
        drawList(g, mouseX, mouseY);
        drawEditor(g, mouseX, mouseY);
        drawSide(g, mouseX, mouseY);
        drawStatusBar(g);

        if (valueBox != null) {
            valueBox.render(g, mouseX, mouseY, partialTick);
        }
    }

    private void drawTitleBar(GuiGraphics g) {
        Ui.textShadow(g, this.font, "TaCZ 调校台", px0 + 10, py0 + 9, Ui.TEXT);
        String sub = "枪械数据可视化调整 · 覆盖式写入，不动原枪包";
        Ui.text(g, this.font, sub, px0 + 10 + this.font.width("TaCZ 调校台") + 10, py0 + 10, Ui.TEXT_FAINT);

        String right = "已写回 " + appliedCache.size() + " 把枪";
        Ui.text(g, this.font, right, px1 - 10 - this.font.width(right), py0 + 10, Ui.TEXT_DIM);
        Ui.hLine(g, px0 + 6, px1 - 6, py0 + 24, Ui.BORDER_SOFT);
    }

    private void drawStatusBar(GuiGraphics g) {
        if (status.isEmpty()) {
            return;
        }
        long age = System.currentTimeMillis() - statusAt;
        if (age > 8000) {
            return;
        }
        int alpha = age > 6500 ? (int) (255 * (8000 - age) / 1500.0) : 255;
        int color = (statusColor & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
        Ui.text(g, this.font, status, fieldX0, py1 - 16, color);
    }

    // ---------------------------------------------------------------- 左栏

    private void drawList(GuiGraphics g, int mouseX, int mouseY) {
        Ui.panel(g, listX0, listY0, listX1, listY1, Ui.PANEL_2, Ui.BORDER_SOFT);

        int sx0 = listX0 + 4;
        int sx1 = listX1 - 4;
        g.fill(sx0, listY0 + 4, sx1, listY0 + 20, 0xFF14171B);
        Ui.text(g, this.font, "⌕", sx0 + 5, listY0 + 8, Ui.TEXT_FAINT);
        searchBox.setX(sx0 + 16);
        searchBox.setY(listY0 + 8);
        searchBox.setWidth(Math.max(40, sx1 - sx0 - 20));
        searchBox.render(g, mouseX, mouseY, 0);
        if (searchBox.getValue().isEmpty()) {
            Ui.text(g, this.font, "搜索枪械 / ID", sx0 + 18, listY0 + 8, Ui.TEXT_FAINT);
        }

        drawTab(g, "枪械", WorkshopRepository.KIND_GUNS, listX0 + 4, tabY, 56, mouseX, mouseY);
        drawTab(g, "配件", WorkshopRepository.KIND_ATTACHMENTS, listX0 + 64, tabY, 56, mouseX, mouseY);

        int top = listTop;
        int bottom = listY1 - 4;
        int rowH = 20;
        int contentH = filtered.size() * rowH;
        int viewH = Math.max(1, bottom - top);
        listScroll = clamp(listScroll, 0, Math.max(0, contentH - viewH));

        Ui.scissorOn(g, listX0 + 2, top, listX1 - 2, bottom);
        for (int i = 0; i < filtered.size(); i++) {
            int y = top + i * rowH - listScroll;
            if (y + rowH < top || y > bottom) {
                continue;
            }
            GunEntry e = filtered.get(i);
            boolean hover = inside(mouseX, mouseY, listX0 + 2, y, listX1 - 2, y + rowH);
            if (e.equals(selected)) {
                g.fill(listX0 + 2, y, listX1 - 2, y + rowH, Ui.ROW_SELECTED);
                g.fill(listX0 + 2, y, listX0 + 4, y + rowH, Ui.ACCENT);
            } else if (hover) {
                g.fill(listX0 + 2, y, listX1 - 2, y + rowH, Ui.ROW_HOVER);
            }
            String name = Ui.trim(this.font, displayName(e), listX1 - listX0 - 44);
            ItemStack icon = iconFor(e);
            int textX = listX0 + 10;
            if (!icon.isEmpty()) {
                try {
                    g.renderItem(icon, listX0 + 6, y + 2);
                } catch (Throwable ignored) {
                    // 渲染失败就退化成纯文字
                }
                textX = listX0 + 27;
                name = Ui.trim(this.font, displayName(e), listX1 - textX - 30);
            }
            Ui.text(g, this.font, name, textX, y + 6, e.equals(selected) ? Ui.TEXT : 0xFFC7CCD2);
            String type = typeLabel(e.type);
            if (!type.isEmpty()) {
                Ui.text(g, this.font, type, listX1 - 8 - this.font.width(type), y + 6, Ui.TEXT_FAINT);
            }
            if (hasEdits(e)) {
                g.fill(listX1 - 6, y + 8, listX1 - 4, y + 12, Ui.ACCENT);
            }
        }
        Ui.scissorOff(g);

        if (contentH > viewH) {
            int barH = Math.max(12, viewH * viewH / contentH);
            int barY = top + (int) ((viewH - barH) * (listScroll / (double) Math.max(1, contentH - viewH)));
            g.fill(listX1 - 3, barY, listX1 - 1, barY + barH, 0x66FFFFFF);
        }
    }

    private void drawTab(GuiGraphics g, String label, String value, int x, int y, int w, int mouseX, int mouseY) {
        boolean active = kind.equals(value);
        boolean hover = inside(mouseX, mouseY, x, y, x + w, y + 14);
        int fill = active ? Ui.ACCENT_SOFT : (hover ? 0x22FFFFFF : 0);
        if (fill != 0) {
            g.fill(x, y, x + w, y + 14, fill);
        }
        if (active) {
            g.fill(x, y + 13, x + w, y + 14, Ui.ACCENT);
        }
        Ui.text(g, this.font, label, x + (w - this.font.width(label)) / 2, y + 3, active ? Ui.ACCENT : Ui.TEXT_DIM);
        hits.add(new Hit(x, y, x + w, y + 14, value, Action.TAB));
    }

    // ---------------------------------------------------------------- 中栏

    private void drawEditor(GuiGraphics g, int mouseX, int mouseY) {
        if (selected == null) {
            String hint = filtered.isEmpty()
                    ? "没扫到枪械数据。确认 tacz 目录里有枪包，然后点「重新扫描枪包」。"
                    : "← 选一把枪开始调";
            Ui.text(g, this.font, hint, fieldX0, fieldY0, Ui.TEXT_DIM);
            return;
        }

        String title = displayName(selected);
        Ui.textShadow(g, this.font, title, fieldX0, py0 + 31, Ui.TEXT);
        int nameW = this.font.width(title);
        String idText = selected.namespace + ":" + selected.idWithoutSuffix();
        Ui.text(g, this.font, idText, fieldX0 + nameW + 8, py0 + 32, Ui.TEXT_FAINT);
        String type = typeLabel(selected.type);
        if (!type.isEmpty()) {
            Ui.tag(g, this.font, type, fieldX0 + nameW + 12 + this.font.width(idText), py0 + 30, Ui.ACCENT);
        }
        String src = selected.sourceLabel;
        int srcW = this.font.width(src);
        Ui.text(g, this.font, src, fieldX1 - srcW, py0 + 32, Ui.TEXT_FAINT);

        drawStats(g);

        if (docError != null) {
            Ui.text(g, this.font, "这个文件解析失败：" + docError, fieldX0, fieldY0, Ui.DANGER);
            return;
        }

        int top = fieldY0;
        int bottom = fieldY1;
        int viewH = Math.max(1, bottom - top);
        fieldScroll = clamp(fieldScroll, 0, Math.max(0, fieldContentHeight - viewH));

        Ui.scissorOn(g, fieldX0 - 2, top, fieldX1 + 2, bottom);
        int y = top - fieldScroll;
        for (Row row : rows) {
            if (row.header() != null) {
                if (y + 20 >= top && y <= bottom) {
                    Ui.hLine(g, fieldX0, fieldX1, y + 16, Ui.BORDER_SOFT);
                    Ui.text(g, this.font, row.header(), fieldX0, y + 4, Ui.ACCENT);
                }
                y += 20;
                continue;
            }
            if (y + 18 > top && y < bottom) {
                drawFieldRow(g, row, y, mouseX, mouseY);
            }
            y += 18;
        }
        Ui.scissorOff(g);

        if (fieldContentHeight > viewH) {
            int barH = Math.max(12, viewH * viewH / fieldContentHeight);
            int barY = top + (int) ((viewH - barH) * (fieldScroll / (double) Math.max(1, fieldContentHeight - viewH)));
            g.fill(fieldX1 + 4, barY, fieldX1 + 6, barY + barH, 0x66FFFFFF);
        }
    }

    private void drawStats(GuiGraphics g) {
        Stats s = stats();
        if (s == null) {
            return;
        }
        int y = py0 + 45;
        int x = fieldX0;
        List<String[]> cells = new ArrayList<>();
        cells.add(new String[]{"单发", fmt(s.damagePerShot)});
        if (s.damagePerPellet < s.damagePerShot - 1.0E-6) {
            cells.add(new String[]{"每颗", fmt(s.damagePerPellet)});
        }
        cells.add(new String[]{"爆头", fmt(s.headshotDamage)});
        cells.add(new String[]{"DPS", fmt(s.dps)});
        cells.add(new String[]{"打空弹匣", fmt(s.magDumpSeconds) + "s"});
        cells.add(new String[]{"击杀 20 血", fmt(s.ttkSeconds) + "s"});
        for (String[] cell : cells) {
            g.fill(x, y, x + 76, y + 24, 0x22FFFFFF);
            Ui.text(g, this.font, cell[0], x + 6, y + 3, Ui.TEXT_FAINT);
            Ui.text(g, this.font, cell[1], x + 6, y + 13, Ui.ACCENT);
            x += 80;
        }
        if (!edits.isEmpty()) {
            String tip = "● 已改动 " + edits.size() + " 项" + (pendingApply ? "，未应用" : "，已应用");
            Ui.text(g, this.font, tip, x + 4, y + 8, pendingApply ? Ui.ACCENT : Ui.OK);
        }
    }

    private Stats stats() {
        if (selected == null || docError != null) {
            return null;
        }
        if (!cacheDirty) {
            return statsCache;
        }
        cacheDirty = false;
        try {
            effectiveCache = edits.isEmpty()
                    ? selected.doc().root()
                    : JsonDoc.parse(selected.originalDoc().render(edits)).root();
            statsCache = Stats.compute(effectiveCache);
        } catch (RuntimeException e) {
            statsCache = null;
        }
        return statsCache;
    }

    private void drawFieldRow(GuiGraphics g, Row row, int y, int mouseX, int mouseY) {
        FieldSpec spec = row.spec();
        String path = row.path();
        JsonNode node = node(path);

        String label = spec != null ? spec.label() : path;
        String unit = spec != null ? spec.unit() : "";
        boolean isDirty = dirty(path);
        int labelColor = isDirty ? Ui.ACCENT : (node == null ? Ui.TEXT_FAINT : Ui.TEXT_DIM);
        Ui.text(g, this.font, Ui.trim(this.font, label, 110), fieldX0, y + 5, labelColor);

        int ctrlX0 = fieldX0 + 118;
        int resetX = fieldX1 - 6;
        int valueRight = resetX - 10;
        int ctrlX1 = valueRight - 62;

        if (spec != null && spec.isNumeric()) {
            double v = number(path, spec.min());
            double span = spec.max() - spec.min();
            double p = span <= 0 ? 0 : (v - spec.min()) / span;
            int ty = y + 7;
            boolean hover = inside(mouseX, mouseY, ctrlX0, ty - 5, ctrlX1, ty + 6);
            Ui.track(g, ctrlX0, ty, ctrlX1, ty + 3, p, isDirty || hover);
            hits.add(new Hit(ctrlX0, ty - 5, ctrlX1, ty + 6, path, Action.SLIDER));
            String val = fmt(v) + (unit.isEmpty() ? "" : " " + unit);
            String shown = Ui.trim(this.font, val, 62);
            Ui.text(g, this.font, shown, valueRight - this.font.width(shown), y + 5, isDirty ? Ui.ACCENT : Ui.TEXT);
            hits.add(new Hit(valueRight - 62, y + 2, valueRight, y + 14, path, Action.TEXT));
        } else if (spec != null && spec.kind() == FieldSpec.Kind.BOOL) {
            boolean b = bool(path);
            int bx = ctrlX0 + 2;
            int by = y + 4;
            g.fill(bx, by, bx + 11, by + 11, b ? Ui.ACCENT : Ui.SLOT);
            g.fill(bx + 1, by + 1, bx + 10, by + 10, Ui.PANEL);
            Ui.text(g, this.font, b ? "开启" : "关闭", bx + 16, y + 5, b ? Ui.ACCENT : Ui.TEXT_FAINT);
            hits.add(new Hit(bx - 4, by - 3, bx + 46, by + 14, path, Action.TOGGLE));
        } else if (spec != null && spec.kind() == FieldSpec.Kind.ENUM) {
            String cur = asText(path);
            if (cur.isEmpty() && !spec.options().isEmpty()) {
                cur = spec.options().get(0);
            }
            int arrowW = 10;
            Ui.text(g, this.font, "◀", ctrlX0, y + 5, Ui.TEXT_FAINT);
            hits.add(new Hit(ctrlX0 - 2, y + 2, ctrlX0 + arrowW, y + 14, path, Action.ENUM_PREV));
            Ui.text(g, this.font, cur, ctrlX0 + arrowW + 4, y + 5, isDirty ? Ui.ACCENT : Ui.TEXT);
            int nextX = ctrlX0 + arrowW + 8 + this.font.width(cur);
            Ui.text(g, this.font, "▶", nextX, y + 5, Ui.TEXT_FAINT);
            hits.add(new Hit(nextX - 2, y + 2, nextX + arrowW, y + 14, path, Action.ENUM_NEXT));
        } else {
            boolean isBool = node != null && node.isBoolean();
            if (isBool) {
                boolean b = bool(path);
                g.fill(ctrlX0 + 2, y + 4, ctrlX0 + 13, y + 15, b ? Ui.ACCENT : Ui.SLOT);
                Ui.text(g, this.font, b ? "开启" : "关闭", ctrlX0 + 18, y + 5, b ? Ui.ACCENT : Ui.TEXT_FAINT);
                hits.add(new Hit(ctrlX0, y + 2, ctrlX0 + 60, y + 16, path, Action.TOGGLE));
            } else {
                String cur = spec != null && spec.kind() == FieldSpec.Kind.TEXT ? asText(path)
                        : (node == null ? "" : (node.isString() ? node.asString() : node.raw()));
                String shown = Ui.trim(this.font, cur, valueRight - ctrlX0);
                Ui.text(g, this.font, shown, ctrlX0, y + 5,
                        isDirty ? Ui.ACCENT : (node == null ? Ui.TEXT_FAINT : Ui.TEXT));
                hits.add(new Hit(ctrlX0 - 2, y + 2, valueRight, y + 16, path, Action.TEXT));
            }
        }

        if (isDirty) {
            Ui.text(g, this.font, "×", resetX, y + 5, Ui.DANGER);
            hits.add(new Hit(resetX - 2, y + 2, resetX + 8, y + 14, path, Action.RESET));
        }
    }

    // ---------------------------------------------------------------- 右栏

    private void drawSide(GuiGraphics g, int mouseX, int mouseY) {
        Ui.panel(g, sideX0, sideY0, sideX1, sideY1, Ui.PANEL_2, Ui.BORDER_SOFT);
        int x = sideX0 + 8;
        int w = sideX1 - sideX0 - 16;
        int y = sideY0 + 10;

        Ui.text(g, this.font, "操作", x, y, Ui.TEXT_DIM);
        y += 14;

        y = button(g, x, y, w, "应用并重载", Action.APPLY, mouseX, mouseY, hasAnyEdits()) + 4;
        y = button(g, x, y, w, "撤销本枪改动", Action.RESET_GUN, mouseX, mouseY, !edits.isEmpty()) + 4;
        y = button(g, x, y, w, "全部还原", Action.RESET_ALL, mouseX, mouseY,
                hasAnyEdits() || !appliedCache.isEmpty()) + 4;
        y = button(g, x, y, w, "重新扫描枪包", Action.RESCAN, mouseX, mouseY, true) + 8;

        Ui.hLine(g, x, x + w, y, Ui.BORDER_SOFT);
        y += 8;
        Ui.text(g, this.font, "已改枪械 " + countEditedEntries() + " 把", x, y, Ui.TEXT_DIM);
        y += 11;
        Ui.text(g, this.font, "已写回 " + appliedCache.size() + " 把", x, y, Ui.TEXT_DIM);
        y += 15;

        Ui.text(g, this.font, "说明", x, y, Ui.ACCENT);
        y += 12;
        y = paragraph(g, x, y, w, "改动直接写回枪包文件，改之前整份备份。");
        y = paragraph(g, x, y, w, "点「全部还原」按备份回滚。");
        y = paragraph(g, x, y, w, "拖动滑条调整，点数值可直接输入，Shift 拖动微调。");

        if (WorkshopService.worldDir() == null) {
            Ui.text(g, this.font, "不在存档里，只能预览", x, sideY1 - 14, Ui.DANGER);
        }
    }

    private int paragraph(GuiGraphics g, int x, int y, int width, String text) {
        for (String line : wrap(text, width)) {
            Ui.text(g, this.font, line, x, y, Ui.TEXT_FAINT);
            y += 10;
        }
        return y + 3;
    }

    private List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (this.font.width(line.toString() + c) > width && line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
            }
            line.append(c);
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    private int button(GuiGraphics g, int x, int y, int w, String label, Action action,
                       int mouseX, int mouseY, boolean enabled) {
        boolean hover = enabled && inside(mouseX, mouseY, x, y, x + w, y + 16);
        int fill = action == Action.APPLY && enabled
                ? (hover ? 0xFFF3B759 : Ui.ACCENT)
                : (hover ? 0x33FFFFFF : 0x1AFFFFFF);
        g.fill(x, y, x + w, y + 16, fill);
        int textColor = action == Action.APPLY && enabled ? 0xFF241A08 : (enabled ? Ui.TEXT : Ui.TEXT_FAINT);
        Ui.text(g, this.font, label, x + (w - this.font.width(label)) / 2, y + 4, textColor);
        if (enabled) {
            hits.add(new Hit(x, y, x + w, y + 16, "", action));
        }
        return y + 16;
    }

    // ================================================================ 输入

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (valueBox != null) {
            if (valueBox.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            commitValueBox();
            return true;
        }
        if (button == 0 && searchBox.mouseClicked(mouseX, mouseY, button)) {
            searchBox.setFocused(true);
            return true;
        }
        searchBox.setFocused(false);

        if (mouseX >= listX0 + 2 && mouseX < listX1 - 2 && mouseY >= listTop && mouseY < listY1 - 4) {
            int idx = (int) ((mouseY - listTop + listScroll) / 20);
            if (idx >= 0 && idx < filtered.size()) {
                select(filtered.get(idx));
                return true;
            }
        }

        for (Hit hit : hits) {
            if (!inside(mouseX, mouseY, hit.x0(), hit.y0(), hit.x1(), hit.y1())) {
                continue;
            }
            switch (hit.action()) {
                case TAB -> {
                    switchKind(hit.path());
                    return true;
                }
                case SLIDER -> {
                    dragging = true;
                    dragPath = hit.path();
                    applySlider(hit, mouseX);
                    return true;
                }
                case TEXT -> {
                    if (button == 1) {
                        reset(hit.path());
                    } else {
                        openValueBox(hit.path());
                    }
                    return true;
                }
                case ENUM_PREV -> {
                    cycleEnum(hit.path(), -1);
                    return true;
                }
                case ENUM_NEXT -> {
                    cycleEnum(hit.path(), 1);
                    return true;
                }
                case TOGGLE -> {
                    setLiteral(hit.path(), JsonDoc.boolLiteral(!bool(hit.path())));
                    return true;
                }
                case RESET -> {
                    reset(hit.path());
                    return true;
                }
                case APPLY -> {
                    applyAll();
                    return true;
                }
                case RESET_GUN -> {
                    edits.clear();
                    cacheDirty = true;
                    try {
                        boolean restored = WorkshopService.restoreOne(selected.key());
                        ClientReloader.requestReload();
                        refreshApplied();
                        setStatus(restored ? "已还原这把枪（含备份回滚）" : "这把枪还没写回过，无需还原", Ui.OK);
                    } catch (Exception e) {
                        setStatus("内存改动已清，但回滚失败：" + e.getMessage(), Ui.DANGER);
                    }
                    return true;
                }
                case RESET_ALL -> {
                    EDITS.clear();
                    edits.clear();
                    cacheDirty = true;
                    pendingApply = false;
                    try {
                        int n = WorkshopService.restoreAll();
                        ClientReloader.requestReload();
                        refreshApplied();
                        setStatus("已全部还原（回滚 " + n + " 个文件）", Ui.OK);
                    } catch (Exception e) {
                        setStatus("还原失败：" + e.getMessage(), Ui.DANGER);
                    }
                    return true;
                }
                case RESCAN -> {
                    doRescan();
                    return true;
                }
                default -> {
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging && dragPath != null) {
            for (Hit hit : hits) {
                if (hit.action() == Action.SLIDER && hit.path().equals(dragPath)) {
                    applySlider(hit, mouseX);
                    return true;
                }
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        dragPath = null;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (inside(mouseX, mouseY, listX0, listTop, listX1, listY1)) {
            listScroll -= (int) (delta * 20);
            return true;
        }
        if (inside(mouseX, mouseY, fieldX0, fieldY0, fieldX1, fieldY1)) {
            fieldScroll -= (int) (delta * 24);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (valueBox != null) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                commitValueBox();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                valueBox = null;
                editingPath = null;
                return true;
            }
            return valueBox.keyPressed(keyCode, scanCode, modifiers);
        }
        if (searchBox.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                searchBox.setFocused(false);
                return true;
            }
            return searchBox.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (valueBox != null) {
            return valueBox.charTyped(codePoint, modifiers);
        }
        if (searchBox.isFocused()) {
            return searchBox.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    // ================================================================ 行为

    private void switchKind(String newKind) {
        if (kind.equals(newKind)) {
            return;
        }
        kind = newKind;
        lastKind = newKind;
        selected = null;
        lastSelectedKey = null;
        listScroll = 0;
        refreshList();
        if (!filtered.isEmpty()) {
            select(filtered.get(0));
        } else {
            edits = new LinkedHashMap<>();
            cacheDirty = true;
        }
    }

    private void applySlider(Hit hit, double mouseX) {
        FieldSpec spec = schema.find(kindOf(selected), hit.path());
        if (spec == null) {
            return;
        }
        double t = (mouseX - hit.x0()) / Math.max(1.0, hit.x1() - hit.x0());
        t = Math.max(0, Math.min(1, t));
        double v = spec.min() + t * (spec.max() - spec.min());
        if (Screen.hasShiftDown()) {
            double base = number(hit.path(), spec.min());
            v = base + Math.signum(v - base) * spec.step() / 10.0;
        }
        setNumber(hit.path(), spec.clamp(v));
    }

    private void cycleEnum(String path, int step) {
        FieldSpec spec = schema.find(kindOf(selected), path);
        if (spec == null || spec.options().isEmpty()) {
            return;
        }
        String cur = asText(path);
        int idx = spec.options().indexOf(cur);
        if (idx < 0) {
            idx = 0;
        } else {
            idx = Math.floorMod(idx + step, spec.options().size());
        }
        setLiteral(path, JsonDoc.quote(spec.options().get(idx)));
    }

    private void openValueBox(String path) {
        Hit target = null;
        for (Hit hit : hits) {
            if (hit.action() == Action.TEXT && hit.path().equals(path)) {
                target = hit;
                break;
            }
        }
        if (target == null) {
            return;
        }
        editingPath = path;
        JsonNode n = node(path);
        FieldSpec spec = schema.find(kindOf(selected), path);
        editingText = spec != null ? spec.kind() == FieldSpec.Kind.TEXT : (n != null && n.isString());
        String current = editingText ? asText(path) : fmt(number(path, 0));
        int w = Math.max(70, target.x1() - target.x0());
        valueBox = new EditBox(this.font, target.x0(), target.y0(), w, 12, Component.empty());
        valueBox.setValue(current);
        valueBox.setMaxLength(64);
        valueBox.setBordered(false);
        valueBox.setTextColor(Ui.ACCENT);
        valueBox.setFocused(true);
        valueBox.setHighlightPos(current.length());
    }

    private void commitValueBox() {
        if (valueBox == null || editingPath == null) {
            valueBox = null;
            return;
        }
        String raw = valueBox.getValue().trim();
        try {
            if (editingText) {
                setLiteral(editingPath, JsonDoc.quote(raw));
            } else if ("true".equals(raw) || "false".equals(raw)) {
                setLiteral(editingPath, JsonDoc.boolLiteral(Boolean.parseBoolean(raw)));
            } else {
                double v = Double.parseDouble(raw);
                FieldSpec spec = schema.find(kindOf(selected), editingPath);
                setNumber(editingPath, spec == null ? v : spec.clamp(v));
            }
        } catch (NumberFormatException e) {
            setStatus("输入的不是数字：" + raw, Ui.DANGER);
        }
        valueBox = null;
        editingPath = null;
    }

    private void applyAll() {
        try {
            Map<GunEntry, String> files = WorkshopService.buildFiles(EDITS, repo);
            int written = WorkshopService.apply(files);
            ClientReloader.requestReload();
            refreshApplied();
            pendingApply = false;
            setStatus("已写回 " + written + " 个枪包文件（已备份），正在重载；数值没变就退出存档再进一次", Ui.OK);
        } catch (Exception e) {
            TaczBench.LOGGER.warn("应用失败", e);
            setStatus("应用失败：" + e.getMessage(), Ui.DANGER);
        }
    }

    private void doRescan() {
        try {
            WorkshopService.rescan();
            repo = WorkshopService.repository();
            selected = null;
            refreshList();
            refreshApplied();
            if (!filtered.isEmpty()) {
                select(filtered.get(0));
            }
            setStatus("重新扫描完成，扫到 " + repo.size() + " 个数据文件", Ui.OK);
        } catch (Exception e) {
            setStatus("扫描失败：" + e.getMessage(), Ui.DANGER);
        }
    }

    private void setStatus(String text, int color) {
        this.status = text;
        this.statusColor = color;
        this.statusAt = System.currentTimeMillis();
    }

    private boolean hasAnyEdits() {
        for (Map<String, String> m : EDITS.values()) {
            if (!m.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private int countEditedEntries() {
        int n = 0;
        for (Map<String, String> m : EDITS.values()) {
            if (!m.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private boolean hasEdits(GunEntry e) {
        Map<String, String> m = EDITS.get(e.key());
        return m != null && !m.isEmpty();
    }

    // ================================================================ 小工具

    private String displayName(GunEntry e) {
        if (e.nameKey != null && !e.nameKey.isBlank()) {
            String s = Component.translatable(e.nameKey).getString();
            if (!s.equals(e.nameKey)) {
                return s;
            }
        }
        return e.idWithoutSuffix();
    }

    /** 拿枪的真实物品图标（和物品栏里那个一模一样）。 */
    private ItemStack iconFor(GunEntry e) {
        if (iconCache.containsKey(e.key())) {
            return iconCache.get(e.key());
        }
        ItemStack stack = ItemStack.EMPTY;
        try {
            String itemPath = e.isGun() ? "modern_kinetic_gun" : "attachment";
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation("tacz", itemPath));
            if (item != null) {
                stack = new ItemStack(item);
                CompoundTag tag = stack.getOrCreateTag();
                String id = e.namespace + ":" + e.idWithoutSuffix();
                tag.putString(e.isGun() ? "GunId" : "AttachmentId", id);
            }
        } catch (Throwable t) {
            stack = ItemStack.EMPTY;
        }
        iconCache.put(e.key(), stack);
        return stack;
    }

    private String typeLabel(String type) {
        return switch (type == null ? "" : type) {
            case "rifle" -> "步枪";
            case "sniper" -> "狙击";
            case "shotgun" -> "霰弹";
            case "smg" -> "冲锋";
            case "pistol" -> "手枪";
            case "mg" -> "机枪";
            case "rpg" -> "火箭";
            case "grenade" -> "投掷";
            case "block" -> "方块";
            default -> "";
        };
    }

    private static String fmt(double v) {
        double r = Math.round(v * 10000.0) / 10000.0;
        if (r == Math.rint(r)) {
            return Long.toString((long) r);
        }
        return BigDecimal.valueOf(r).stripTrailingZeros().toPlainString();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static boolean inside(double mx, double my, int x0, int y0, int x1, int y1) {
        return mx >= x0 && mx < x1 && my >= y0 && my < y1;
    }

    // ================================================================ 记录类型

    private record Row(String header, FieldSpec spec, String path) {
    }

    private record Hit(int x0, int y0, int x1, int y1, String path, Action action) {
    }

    private enum Action {
        TAB, SLIDER, TEXT, ENUM_PREV, ENUM_NEXT, TOGGLE, RESET,
        APPLY, RESET_GUN, RESET_ALL, RESCAN
    }
}
