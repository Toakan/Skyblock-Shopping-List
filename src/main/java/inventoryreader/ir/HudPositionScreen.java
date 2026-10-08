package inventoryreader.ir;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * "Move HUD": drag a panel to move it, drag a corner to resize. Shows every active panel (the shopping list,
 * plus Craftable / Forging when they have their own). Opened by B and from Settings.
 */
public class HudPositionScreen extends Screen {
    private static final int GOLD = 0xFFFFB728;
    private static final int HANDLE = 10;
    private enum Corner { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private final Screen parent;
    private final SandboxWidget widget;
    /** Working copies: position in GUI pixels, size in HUD units. */
    private final Map<SandboxWidget.Panel, SandboxWidget.PanelRect> rects = new EnumMap<>(SandboxWidget.Panel.class);
    private SandboxWidget.Panel active;
    private boolean dragging = false;
    private Corner resizing = Corner.NONE;
    private int dragOffsetX, dragOffsetY;
    private int startMouseX, startMouseY;
    private SandboxWidget.PanelRect startRect;

    /** @param parent screen to return to on Done, or null to close. */
    public HudPositionScreen(Screen parent) {
        super(Component.literal("Move HUD"));
        this.parent = parent;
        this.widget = SandboxWidget.getInstance();
        loadRects();
    }

    private void loadRects() {
        rects.clear();
        for (SandboxWidget.Panel panel : SandboxWidget.activePanels()) rects.put(panel, widget.getPanelRect(panel));
    }

    @Override
    protected void init() {
        int buttonWidth = 90;
        addRenderableWidget(Button.builder(Component.literal("Reset position"), button -> {
            widget.resetPanelPositions();
            loadRects();
        }).bounds(width - 2 * buttonWidth - 8, 1, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
            .bounds(width - buttonWidth - 4, 1, buttonWidth, 20).build());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xFF0E0E0E);
        for (int x = 0; x < width; x += 50) context.fill(x, 0, x + 1, height, 0x22FFFFFF);
        for (int y = 0; y < height; y += 50) context.fill(0, y, width, y + 1, 0x22FFFFFF);
        context.fill(0, 0, width, 22, 0xFF17293A);
        context.outline(0, 0, width, 22, 0xFF223344);
        context.text(font, "Move HUD", 8, 7, GOLD, false);
        // Hint and position in the title bar, shortened to whatever fits left of the buttons.
        SandboxWidget.PanelRect mainRect = rects.get(SandboxWidget.Panel.MAIN);
        String hint = "Drag to move, drag a corner to resize";
        if (mainRect != null) {
            hint += "   X=" + mainRect.x + " Y=" + mainRect.y + " W=" + mainRect.width + " H=" + mainRect.height
                + " Scale=" + HudStyle.get().hudScale + "%";
        }
        int hintX = 8 + font.width("Move HUD") + 12;
        context.text(font, font.plainSubstrByWidth(hint, Math.max(0, width - 2 * 90 - 16 - hintX)), hintX, 7, 0xFFBBBBBB, false);

        float scale = SandboxWidget.scaleFactor();
        for (Map.Entry<SandboxWidget.Panel, SandboxWidget.PanelRect> e : rects.entrySet()) {
            SandboxWidget.Panel panel = e.getKey();
            SandboxWidget.PanelRect r = e.getValue();
            int w = Math.round(r.width * scale);
            int h = Math.round(r.height * scale);
            // Opaque backing so the grid doesn't show through a see-through panel colour.
            context.fill(r.x, r.y, r.x + w, r.y + h, 0xFF0E0E0E);
            if (widget.renderPanel(context, panel, r.x, r.y, r.width, r.height, true) == 0) {
                context.fill(r.x, r.y, r.x + w, r.y + h, 0xCC271910);
                context.text(font, "Shopping list is empty", r.x + 8, r.y + 8, 0xFFFFFFFF, false);
            }
            boolean busy = panel == active && (dragging || resizing != Corner.NONE);
            context.outline(r.x, r.y, w, h, busy ? 0xFFFFDD00 : 0x88FFFFFF);
            for (int[] c : corners(r.x, r.y, w, h)) {
                context.fill(c[0] - HANDLE / 2, c[1] - HANDLE / 2, c[0] + HANDLE / 2, c[1] + HANDLE / 2, GOLD);
            }
        }
        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    private static int[][] corners(int x, int y, int w, int h) {
        return new int[][] {{x, y}, {x + w, y}, {x, y + h}, {x + w, y + h}};
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent ctx, boolean doubleClick) {
        if (super.mouseClicked(ctx, doubleClick)) return true;
        int mouseX = (int) ctx.x();
        int mouseY = (int) ctx.y();
        float scale = SandboxWidget.scaleFactor();
        // Last drawn is on top, so check in reverse.
        List<SandboxWidget.Panel> order = new ArrayList<>(rects.keySet());
        for (int i = order.size() - 1; i >= 0; i--) {
            SandboxWidget.Panel panel = order.get(i);
            SandboxWidget.PanelRect r = rects.get(panel);
            int w = Math.round(r.width * scale);
            int h = Math.round(r.height * scale);
            Corner corner = cornerAt(mouseX, mouseY, r.x, r.y, w, h);
            if (corner != Corner.NONE || (mouseX >= r.x && mouseX <= r.x + w && mouseY >= r.y && mouseY <= r.y + h)) {
                active = panel;
                resizing = corner;
                dragging = corner == Corner.NONE;
                dragOffsetX = mouseX - r.x;
                dragOffsetY = mouseY - r.y;
                startMouseX = mouseX;
                startMouseY = mouseY;
                startRect = new SandboxWidget.PanelRect(r.x, r.y, r.width, r.height);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent ctx, double deltaX, double deltaY) {
        if (active == null || (!dragging && resizing == Corner.NONE)) return super.mouseDragged(ctx, deltaX, deltaY);
        int mouseX = (int) ctx.x();
        int mouseY = (int) ctx.y();
        SandboxWidget.PanelRect r = rects.get(active);
        if (dragging) {
            r.x = Math.max(0, Math.min(width - 20, mouseX - dragOffsetX));
            r.y = Math.max(0, Math.min(height - 20, mouseY - dragOffsetY));
            return true;
        }
        float scale = SandboxWidget.scaleFactor();
        // Mouse movement in HUD units.
        int dx = Math.round((mouseX - startMouseX) / scale);
        int dy = Math.round((mouseY - startMouseY) / scale);
        int minW = SandboxWidget.minWidth(active);
        int minH = SandboxWidget.minHeight(active);
        boolean left = resizing == Corner.TOP_LEFT || resizing == Corner.BOTTOM_LEFT;
        boolean top = resizing == Corner.TOP_LEFT || resizing == Corner.TOP_RIGHT;
        int newW = Math.max(minW, startRect.width + (left ? -dx : dx));
        int newH = Math.max(minH, startRect.height + (top ? -dy : dy));
        r.width = newW;
        r.height = newH;
        // Dragging a left/top corner moves that edge, keeping the opposite one in place.
        r.x = left ? Math.max(0, startRect.x + Math.round((startRect.width - newW) * scale)) : startRect.x;
        r.y = top ? Math.max(0, startRect.y + Math.round((startRect.height - newH) * scale)) : startRect.y;
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent ctx) {
        if (active != null && (dragging || resizing != Corner.NONE)) {
            SandboxWidget.PanelRect r = rects.get(active);
            widget.setPanelRect(active, r.x, r.y, r.width, r.height);
            rects.put(active, widget.getPanelRect(active));
            dragging = false;
            resizing = Corner.NONE;
            return true;
        }
        return super.mouseReleased(ctx);
    }

    private static Corner cornerAt(int mouseX, int mouseY, int x, int y, int w, int h) {
        if (near(mouseX, mouseY, x, y)) return Corner.TOP_LEFT;
        if (near(mouseX, mouseY, x + w, y)) return Corner.TOP_RIGHT;
        if (near(mouseX, mouseY, x, y + h)) return Corner.BOTTOM_LEFT;
        if (near(mouseX, mouseY, x + w, y + h)) return Corner.BOTTOM_RIGHT;
        return Corner.NONE;
    }

    private static boolean near(int mouseX, int mouseY, int cx, int cy) {
        return Math.abs(mouseX - cx) <= HANDLE / 2 && Math.abs(mouseY - cy) <= HANDLE / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
