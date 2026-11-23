package com.datapackitems.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 「数据包物品」页 —— 按数据包 / 子文件夹分页的物品领取界面。
 *
 * <h2>★ 版面尺寸完全对齐原版创造物品栏</h2>
 *
 * <p>数值是从 { @code CreativeInventoryScreen} 的贴图与字节码里量出来的：
 * <pre>
 *   主面板      195 × 136   （tab_items.png 本体大小，和原版创造页一致）
 *   物品格      9 列 × 5 行，每格 18 × 18
 *   页签        贴在上边缘，26 高
 * </pre>
 * 为了塞下「数据包」和「子文件夹」两级分类，面板内部这样排：
 * <pre>
 *   y=5   标题（左）+ 件数（右）
 *   y=18  二级页签：根目录 / book / xiansuo …
 *   y=34  物品格 9×5（永远画满 45 格，跟创造栏一样）
 *   y=126 底部提示
 * </pre>
 *
 * <h2>★ 为什么图标必须自己画（踩过的坑）</h2>
 *
 * <p>{ @link DrawContext#drawItemInSlot} <b>只画数量和耐久条，不画图标</b>。
 * 原版 { @code HandledScreen#drawItem} 是：
 * <pre>
 *   context.drawItem(stack, x, y);                      ← 先画图标
 *   context.drawItemInSlot(textRenderer, stack, x, y);  ← 再叠数量
 * </pre>
 * 少画前者，格子里就是空的 —— 上一版没有图标正是这个原因。
 *
 * <h2>★ 空槽位也要画</h2>
 *
 * <p>创造栏永远是满屏 45 个格子（哪怕是空的），只画"有物品的格子"会显得面板
 * 下半截空荡荡、像坏掉了。
 */
 @Environment(EnvType.CLIENT)
public final class DatapackItemsScreen extends Screen {

    // ---- 原版创造栏尺寸 ----
    private static final int PANEL_W = 195;
    private static final int PANEL_H = 136;

    private static final int COLS = 9;
    private static final int ROWS = 5;
    private static final int SLOT = 18;
    private static final int GRID_W = COLS * SLOT;
    private static final int GRID_H = ROWS * SLOT;

    /** 格子区相对面板的偏移（左、上）。 */
    private static final int GRID_X = 8;
    private static final int GRID_Y = 34;

    /** 二级页签那一行的位置与高度。 */
    private static final int TABS_Y = 18;
    private static final int TABS_H = 14;

    /** 一级页签（面板上边缘之外）。 */
    private static final int PACK_TAB_H = 26;

    /** 滚动条。 */
    private static final int SCROLLER_W = 12;

    // ---- 原版配色（从 tab_items.png 采样）----
    private static final int C_PANEL_BG = 0xFFC6C6C6;
    private static final int C_SLOT_DARK = 0xFF373737;
    private static final int C_SLOT_BG = 0xFF8B8B8B;
    private static final int C_SLOT_HOVER = 0xFFFFFFFF;
    private static final int C_OUTLINE = 0xFF000000;
    private static final int C_SHADOW = 0xFF555555;
    private static final int C_TEXT = 0xFF404040;
    private static final int C_TEXT_DIM = 0xFF707070;
    private static final int C_TEXT_WARN = 0xFFAA0000;
    /** 面板外的暗化（原版容器页是纯黑 + 半透明）。 */
    private static final int C_DIM = 0x99000000;

    /** 页签（一级 = 数据包，二级 = 子文件夹）。 */
    private record Tab(String label, int x1, int y1, int x2, int y2, String key) {
        boolean hit(double mx, double my) {
            return mx >= x1 && mx < x2 && my >= y1 && my < y2;
        }
    }

    /** 格子的命中框 + 内容。 */
    private record Cell(DatapackItemsClientData.Entry entry, int x, int y) {
        boolean hit(double mx, double my) {
            return mx >= x && mx < x + SLOT && my >= y && my < y + SLOT;
        }
    }

    private final List<Tab> packTabs = new ArrayList<>();
    private final List<Tab> groupTabs = new ArrayList<>();
    private final List<Cell> cells = new ArrayList<>();

    private int panelLeft;
    private int panelTop;

    private int selectedPack = 0;
    private String selectedGroup = "";

    private int maxScrollRow = 0;
    private int scrollRow = 0;

    private Cell hoveredCell;
    private boolean overGrid;

    public DatapackItemsScreen() {
        super(Text.translatable("screen.datapack_items.title"));
    }

    // ------------------------------------------------------------------ 初始化

 @Override
    protected void init() {
        super.init();
        panelLeft = (width - PANEL_W) / 2;
        panelTop = (height - PANEL_H) / 2;
        buildPackTabs();
        buildGroupTabs();

        // 保证至少取过一次数据（服务端模式是异步的，收到推送后会自己刷新）
        if (!DatapackItemsClientData.hasData()) {
            DatapackItemsClientData.refresh(client, false);
        }
        rebuildCells();
    }

    public void onCatalogUpdated() {
        clampSelection();
        buildPackTabs();
        buildGroupTabs();
        rebuildCells();
    }

    private void clampSelection() {
        List<DatapackItemsClientData.Pack> packs = DatapackItemsClientData.packs();
        if (packs.isEmpty()) {
            selectedPack = 0;
            selectedGroup = "";
            return;
        }
        selectedPack = Math.max(0, Math.min(selectedPack, packs.size() - 1));
        List<String> groups = DatapackItemsClientData.groupsOf(packs.get(selectedPack));
        if (!groups.contains(selectedGroup)) {
            selectedGroup = groups.isEmpty() ? "" : groups.get(0);
        }
    }

    private DatapackItemsClientData.Pack currentPack() {
        List<DatapackItemsClientData.Pack> packs = DatapackItemsClientData.packs();
        if (packs.isEmpty()) {
            return null;
        }
        return packs.get(Math.max(0, Math.min(selectedPack, packs.size() - 1)));
    }

    // ------------------------------------------------------------------ 布局

    /**
     * 一级页签：贴在面板上边缘的一排小页签。
     *
     * <p>★ 宽度按文字实际宽度算，只有超长时才截断。
     * 早先写死「最多 26 宽」，结果「gubao_datapack」被截成了「gu…」。
     */
    private void buildPackTabs() {
        packTabs.clear();
        int x = panelLeft;
        int y = panelTop - PACK_TAB_H;
        int maxRight = panelLeft + PANEL_W;
        for (int i = 0; i < DatapackItemsClientData.packs().size(); i++) {
            DatapackItemsClientData.Pack pack = DatapackItemsClientData.packs().get(i);
            int avail = maxRight - x;
            if (avail < 30) {
                break;
            }
            // 每个页签最多占一半面板宽，剩下的留给后面的页签
            int labelMax = Math.min(avail - 10, PANEL_W / 2);
            String label = trim(pack.title(), labelMax);
            int w = textRenderer.getWidth(label) + 10;
            packTabs.add(new Tab(label, x, y, x + w, panelTop, String.valueOf(i)));
            x += w + 2;
        }
    }

    /** 二级页签：面板内第一行。 */
    private void buildGroupTabs() {
        groupTabs.clear();
        DatapackItemsClientData.Pack pack = currentPack();
        if (pack == null) {
            return;
        }
        int x = panelLeft + GRID_X;
        int y = panelTop + TABS_Y;
        int maxRight = panelLeft + PANEL_W - GRID_X;
        for (String group : DatapackItemsClientData.groupsOf(pack)) {
            String label = group.isEmpty()
                    ? Text.translatable("label.datapack_items.root").getString()
                    : group;
            int w = textRenderer.getWidth(label) + 8;
            if (x + w > maxRight) {
                break;
            }
            groupTabs.add(new Tab(label, x, y, x + w, y + TABS_H, group));
            x += w + 2;
        }
    }

    private void rebuildCells() {
        cells.clear();
        hoveredCell = null;
        DatapackItemsClientData.Pack pack = currentPack();
        if (pack == null) {
            maxScrollRow = 0;
            scrollRow = 0;
            return;
        }
        List<DatapackItemsClientData.Entry> list =
                DatapackItemsClientData.entriesOf(pack, selectedGroup);
        int totalRows = (list.size() + COLS - 1) / COLS;
        maxScrollRow = Math.max(0, totalRows - ROWS);
        scrollRow = Math.max(0, Math.min(scrollRow, maxScrollRow));

        int start = scrollRow * COLS;
        int gridLeft = panelLeft + GRID_X;
        int gridTop = panelTop + GRID_Y;
        for (int i = start; i < list.size() && i < start + COLS * ROWS; i++) {
            int idx = i - start;
            int cx = gridLeft + (idx % COLS) * SLOT;
            int cy = gridTop + (idx / COLS) * SLOT;
            cells.add(new Cell(list.get(i), cx, cy));
        }
    }

    // ------------------------------------------------------------------ 绘制

 @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        super.renderBackground(context, mouseX, mouseY, delta);

        context.fill(0, 0, width, height, C_DIM);

        drawPackTabs(context, mouseX, mouseY);
        drawPanel(context);
        drawGroupTabs(context, mouseX, mouseY);
        drawGrid(context, mouseX, mouseY);
    }

    /** 面板：原版那种「黑描边 + 灰底 + 左上高光 + 右下阴影」。 */
    private void drawPanel(DrawContext context) {
        int l = panelLeft;
        int t = panelTop;
        int r = panelLeft + PANEL_W;
        int b = panelTop + PANEL_H;

        context.fill(l - 1, t - 1, r + 1, b + 1, C_OUTLINE);
        context.fill(l, t, r, b, C_PANEL_BG);
        context.fill(l, t, r - 1, t + 1, 0xFFFFFFFF);
        context.fill(l, t, l + 1, b - 1, 0xFFFFFFFF);
        context.fill(l + 1, b - 1, r, b, C_SHADOW);
        context.fill(r - 1, t + 1, r, b, C_SHADOW);
    }

    private void drawPackTabs(DrawContext context, int mouseX, int mouseY) {
        for (int i = 0; i < packTabs.size(); i++) {
            Tab tab = packTabs.get(i);
            boolean active = i == selectedPack;
            boolean hover = tab.hit(mouseX, mouseY);
            int x1 = tab.x1();
            int y1 = tab.y1();
            int x2 = tab.x2();
            int y2 = tab.y2();

            // 选中的页签和面板同色，看起来"长"在面板上
            int fill = active ? C_PANEL_BG : (hover ? 0xFFB0B0B0 : 0xFF8B8B8B);
            context.fill(x1, y1, x2, y2 + 2, fill);
            context.fill(x1, y1, x2, y1 + 1, C_OUTLINE);
            context.fill(x1, y1, x1 + 1, y2, C_OUTLINE);
            context.fill(x2 - 1, y1, x2, y2, C_OUTLINE);
            if (active) {
                // 擦掉和面板相接的那一段，视觉上连成一体
                context.fill(x1 + 1, y2 - 1, x2 - 1, y2 + 2, C_PANEL_BG);
            }

            int tx = x1 + (x2 - x1 - textRenderer.getWidth(tab.label())) / 2;
            int ty = y1 + (PACK_TAB_H - 8) / 2;
            context.drawTextWithShadow(textRenderer, Text.literal(tab.label()), tx, ty,
                    active ? C_TEXT : 0xFFFFFFFF);
        }
    }

    private void drawGroupTabs(DrawContext context, int mouseX, int mouseY) {
        for (Tab tab : groupTabs) {
            boolean active = tab.key().equals(selectedGroup);
            boolean hover = tab.hit(mouseX, mouseY);
            int x1 = tab.x1();
            int y1 = tab.y1();
            int x2 = tab.x2();
            int y2 = tab.y2();

            int fill = active ? 0xFF6E6E6E : (hover ? 0xFFB0B0B0 : C_SLOT_BG);
            context.fill(x1, y1, x2, y2, fill);
            context.fill(x1, y1, x2, y1 + 1, 0xFFFFFFFF);
            context.fill(x1, y1, x1 + 1, y2, 0xFFFFFFFF);
            context.fill(x1 + 1, y2 - 1, x2, y2, C_SHADOW);
            context.fill(x2 - 1, y1 + 1, x2, y2, C_SHADOW);

            context.drawTextWithShadow(textRenderer, Text.literal(tab.label()),
                    x1 + 4, y1 + 3, active ? 0xFFFFFFFF : C_TEXT);
        }
    }

    /**
     * 物品网格：<b>永远画满 45 格</b>，再把有物品的格子填上。
     *
     * <p>★ 每格两个绘制调用，顺序不能反：
     * { @code drawItem} 画图标，{ @code drawItemInSlot} 叠数量与耐久条。
     */
    private void drawGrid(DrawContext context, int mouseX, int mouseY) {
        int gridLeft = panelLeft + GRID_X;
        int gridTop = panelTop + GRID_Y;

        hoveredCell = null;
        overGrid = mouseX >= gridLeft && mouseX < gridLeft + GRID_W
                && mouseY >= gridTop && mouseY < gridTop + GRID_H;

        // 先铺满 45 个空槽
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int x = gridLeft + col * SLOT;
                int y = gridTop + row * SLOT;
                drawSlot(context, x, y);
            }
        }

        // 再画物品
        for (Cell cell : cells) {
            boolean hover = cell.hit(mouseX, mouseY);
            if (hover) {
                hoveredCell = cell;
                context.fill(cell.x() + 1, cell.y() + 1,
                        cell.x() + SLOT - 1, cell.y() + SLOT - 1, 0x80FFFFFF);
            }
            if (cell.entry().ok()) {
                ItemStack stack = cell.entry().stack();
                context.drawItem(stack, cell.x() + 1, cell.y() + 1);
                context.drawItemInSlot(textRenderer, stack, cell.x() + 1, cell.y() + 1);
            } else {
                drawBrokenMark(context, cell.x(), cell.y());
            }
        }

        drawScrollbar(context, gridLeft, gridTop);
    }

    /** 一个下沉的格子：深色边框 + 灰底。 */
    private void drawSlot(DrawContext context, int x, int y) {
        context.fill(x, y, x + SLOT, y + SLOT, C_SLOT_DARK);
        context.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, C_SLOT_BG);
    }

    /** 解析失败的格子：红叉 + 淡红底。 */
    private void drawBrokenMark(DrawContext context, int x, int y) {
        context.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, 0xFF6E3A3A);
        int cx = x + SLOT / 2;
        int cy = y + SLOT / 2;
        context.fill(cx - 4, cy - 1, cx + 5, cy + 1, 0xFFFF6E6E);
        context.fill(cx - 1, cy - 4, cx + 1, cy + 5, 0xFFFF6E6E);
    }

    private void drawScrollbar(DrawContext context, int gridLeft, int gridTop) {
        if (maxScrollRow <= 0) {
            return;
        }
        int barX = gridLeft + GRID_W + 2;
        context.fill(barX, gridTop, barX + SCROLLER_W, gridTop + GRID_H, C_SLOT_DARK);
        context.fill(barX + 1, gridTop + 1, barX + SCROLLER_W - 1, gridTop + GRID_H - 1, C_SLOT_BG);

        int trackH = GRID_H - 2;
        int thumbH = Math.max(15, trackH * ROWS / (ROWS + maxScrollRow));
        int travel = trackH - thumbH;
        int thumbY = gridTop + 1 + (maxScrollRow == 0 ? 0 : travel * scrollRow / maxScrollRow);
        context.fill(barX + 1, thumbY, barX + SCROLLER_W - 1, thumbY + thumbH, C_PANEL_BG);
        context.fill(barX + 1, thumbY, barX + SCROLLER_W - 2, thumbY + 1, 0xFFFFFFFF);
    }

 @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        DatapackItemsClientData.Pack pack = currentPack();
        int left = panelLeft + GRID_X;
        int right = panelLeft + PANEL_W - GRID_X;
        int textMax = PANEL_W - GRID_X * 2;

        // ---- 标题 + 件数 ----
        String title = pack == null
                ? Text.translatable("screen.datapack_items.title").getString()
                : pack.title();
        String countText = "";
        if (pack != null) {
            countText = Text.translatable("label.datapack_items.count",
                    DatapackItemsClientData.entriesOf(pack, selectedGroup).size()).getString();
        }
        int countW = textRenderer.getWidth(countText);
        context.drawText(textRenderer, Text.literal(trim(title, textMax - countW - 6)),
                left, panelTop + 5, C_TEXT, false);
        if (!countText.isEmpty()) {
            context.drawText(textRenderer, Text.literal(countText),
                    right - countW, panelTop + 5, C_TEXT_DIM, false);
        }

        // ---- 底部：提示 / 状态 ----
        String footer;
        int footerColor = C_TEXT_DIM;
        if (!DatapackItemsClientData.hasData()) {
            // 服务端模式是异步的，文案要说对，别误导成"扫本地"
            footer = ModeDetector.detect() == ModeDetector.Source.SERVER
                    ? Text.translatable("message.datapack_items.waiting_server").getString()
                    : Text.translatable("message.datapack_items.loading").getString();
        } else if (DatapackItemsClientData.packs().isEmpty()) {
            footer = ModeDetector.detect() == ModeDetector.Source.SERVER
                    ? Text.translatable("message.datapack_items.none_server").getString()
                    : Text.translatable("message.datapack_items.none").getString();
            footerColor = C_TEXT_WARN;
        } else if (cells.isEmpty()) {
            footer = Text.translatable("message.datapack_items.empty_group").getString();
        } else {
            footer = Text.translatable("message.datapack_items.hint").getString();
        }
        // ★ 页脚居中画：这句话比面板宽时会被截断，居中能保证左右对称地截
        String shownFooter = trim(footer, textMax);
        context.drawText(textRenderer, Text.literal(shownFooter),
                panelLeft + (PANEL_W - textRenderer.getWidth(shownFooter)) / 2,
                panelTop + PANEL_H - 11, footerColor, false);

        drawHoverTooltip(context, mouseX, mouseY);
    }

    private void drawHoverTooltip(DrawContext context, int mouseX, int mouseY) {
        for (Tab tab : packTabs) {
            if (tab.hit(mouseX, mouseY)) {
                int idx = parseIndex(tab.key());
                if (idx >= 0 && idx < DatapackItemsClientData.packs().size()) {
                    DatapackItemsClientData.Pack p = DatapackItemsClientData.packs().get(idx);
                    List<Text> lines = new ArrayList<>();
                    lines.add(Text.literal(p.title()).formatted(Formatting.YELLOW));
                    lines.add(Text.translatable("tooltip.datapack_items.pack_count",
                            p.entries().size(), DatapackItemsClientData.groupsOf(p).size())
                            .formatted(Formatting.GRAY));
                    context.drawTooltip(textRenderer, lines, mouseX, mouseY);
                }
                return;
            }
        }

        if (hoveredCell == null) {
            return;
        }
        DatapackItemsClientData.Entry entry = hoveredCell.entry();
        if (!entry.ok()) {
            List<Text> lines = new ArrayList<>();
            lines.add(Text.translatable("tooltip.datapack_items.broken").formatted(Formatting.RED));
            if (entry.error() != null) {
                lines.add(Text.literal(entry.error()).formatted(Formatting.GRAY));
            }
            lines.add(Text.literal(entry.functionId()).formatted(Formatting.DARK_GRAY));
            context.drawTooltip(textRenderer, lines, mouseX, mouseY);
            return;
        }

        List<Text> lines = new ArrayList<>(Screen.getTooltipFromItem(client, entry.stack()));
        lines.add(Text.empty());
        lines.add(Text.translatable("tooltip.datapack_items.take").formatted(Formatting.YELLOW));
        lines.add(Text.literal(entry.functionId()).formatted(Formatting.DARK_GRAY));
        context.drawTooltip(textRenderer, lines, mouseX, mouseY);
    }

    // ------------------------------------------------------------------ 交互

 @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // R = 重新取数据（改完数据包不用退出重进）
        if (keyCode == GLFW.GLFW_KEY_R) {
            if (ModeDetector.detect() == ModeDetector.Source.LOCAL) {
                DatapackItemsClientData.rescanLocal(client);
                onCatalogUpdated();
            } else {
                DatapackItemsClientData.refresh(client, true);
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

 @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (int i = 0; i < packTabs.size(); i++) {
            if (packTabs.get(i).hit(mouseX, mouseY)) {
                if (i != selectedPack) {
                    selectedPack = i;
                    List<String> groups = DatapackItemsClientData.groupsOf(
                            DatapackItemsClientData.packs().get(i));
                    selectedGroup = groups.isEmpty() ? "" : groups.get(0);
                    scrollRow = 0;
                    buildPackTabs();
                    buildGroupTabs();
                    rebuildCells();
                }
                return true;
            }
        }

        for (Tab tab : groupTabs) {
            if (tab.hit(mouseX, mouseY)) {
                if (!tab.key().equals(selectedGroup)) {
                    selectedGroup = tab.key();
                    scrollRow = 0;
                    buildGroupTabs();
                    rebuildCells();
                }
                return true;
            }
        }

        for (Cell cell : cells) {
            if (cell.hit(mouseX, mouseY)) {
                DatapackItemsClientData.Entry entry = cell.entry();
                if (entry.ok()) {
                    if (button == 0) {
                        DatapackItemsClientData.take(client, entry);
                    } else if (button == 1) {
                        copyFunctionId(entry.functionId());
                    }
                }
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void copyFunctionId(String functionId) {
        if (client == null) {
            return;
        }
        client.keyboard.setClipboard("/function " + functionId);
        if (client.player != null) {
            client.player.sendMessage(Text.translatable(
                            "message.datapack_items.copied", functionId)
                    .formatted(Formatting.GRAY), true);
        }
    }

 @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount,
                                 double verticalAmount) {
        if (overGrid && maxScrollRow > 0) {
            int delta = verticalAmount > 0 ? -1 : 1;
            int next = Math.max(0, Math.min(maxScrollRow, scrollRow + delta));
            if (next != scrollRow) {
                scrollRow = next;
                rebuildCells();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

 @Override
    public boolean shouldPause() {
        return false;
    }

    // ------------------------------------------------------------------ 工具

    private static int parseIndex(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String trim(String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        StringBuilder sb = new StringBuilder();
        int w = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int cw = textRenderer.getWidth(String.valueOf(c));
            if (w + cw + textRenderer.getWidth(ellipsis) > maxWidth) {
                break;
            }
            sb.append(c);
            w += cw;
        }
        return sb.append(ellipsis).toString();
    }
}
