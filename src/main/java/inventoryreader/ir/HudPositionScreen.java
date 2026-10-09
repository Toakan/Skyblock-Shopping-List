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
 * "Move HUD": drag a panel to move it, a corner to scale it (keeps its shape), an edge to resize it. Shows every active panel (the shopping list,
 * plus Craftable / Forging when they have their own). Opened by B and from Settings.
 */
public class HudPositionScreen extends Screen {
    private static final int GOLD = MenuTabs.GOLD;
    private static final int HANDLE = 10;
    /** How close (GUI pixels) the mouse must be to an edge to grab it. */
    private static final int EDGE_GRAB = 3;
    private enum Handle {
        NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, TOP, BOTTOM, LEFT, RIGHT;

        boolean isCorner() {
            return this == TOP_LEFT || this == TOP_RIGHT || this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
        }
        boolean left() { return this == TOP_LEFT || this == BOTTOM_LEFT || this == LEFT; }
        boolean top() { return this == TOP_LEFT || this == TOP_RIGHT || this == TOP; }
        boolean horizontal() { return this == LEFT || this == RIGHT; }
    }

    private final Screen parent;
    private final SandboxWidget widget;
    /** Working copies: position in GUI pixels, size in HUD units. */
    private final Map<SandboxWidget.Panel, SandboxWidget.PanelRect> rects = new EnumMap<>(SandboxWidget.Panel.class);
    private SandboxWidget.Panel active;
    private boolean dragging = false;
    private Handle resizing = Handle.NONE;
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
        context.fill(0, 0, width, 22, MenuTabs.HEADER_BG);
        context.outline(0, 0, width, 22, MenuTabs.HEADER_BORDER);
        context.text(font, "Move HUD", 8, 7, GOLD, false);
        // Hint and position in the title bar, shortened to whatever fits left of the buttons.
        SandboxWidget.PanelRect mainRect = rects.get(SandboxWidget.Panel.MAIN);
        SandboxWidget.PanelRect shown = active != null && rects.containsKey(active) ? rects.get(active) : mainRect;
        String hint = "Drag to move, corner to scale, edge to resize";
        if (shown != null) {
            hint += "   X=" + shown.x + " Y=" + shown.y + " W=" + shown.width + " H=" + shown.height
                + " Scale=" + shown.scalePercent() + "%";
        }
        int hintX = 8 + font.width("Move HUD") + 12;
        context.text(font, font.plainSubstrByWidth(hint, Math.max(0, width - 2 * 90 - 16 - hintX)), hintX, 7, 0xFFBBBBBB, false);

        for (Map.Entry<SandboxWidget.Panel, SandboxWidget.PanelRect> e : rects.entrySet()) {
            SandboxWidget.Panel panel = e.getKey();
            SandboxWidget.PanelRect r = e.getValue();
            float scale = scaleOf(r);
            int w = Math.round(r.width * scale);
            int h = Math.round(r.height * scale);
            // Opaque backing so the grid doesn't show through a see-through panel colour.
            context.fill(r.x, r.y, r.x + w, r.y + h, 0xFF0E0E0E);
            if (widget.renderPanel(context, panel, r.x, r.y, r.width, r.height, scale, true) == 0) {
                context.fill(r.x, r.y, r.x + w, r.y + h, 0xCC271910);
                context.text(font, "Shopping list is empty", r.x + 8, r.y + 8, 0xFFFFFFFF, false);
            }
            boolean busy = panel == active && (dragging || resizing != Handle.NONE);
            context.outline(r.x, r.y, w, h, busy ? 0xFFFFDD00 : 0x88FFFFFF);
            // White grips mid-edge (resize), gold squares on the corners (scale).
            int midX = r.x + w / 2;
            int midY = r.y + h / 2;
            context.fill(midX - HANDLE, r.y - 1, midX + HANDLE, r.y + 2, 0xFFFFFFFF);
            context.fill(midX - HANDLE, r.y + h - 2, midX + HANDLE, r.y + h + 1, 0xFFFFFFFF);
            context.fill(r.x - 1, midY - HANDLE, r.x + 2, midY + HANDLE, 0xFFFFFFFF);
            context.fill(r.x + w - 2, midY - HANDLE, r.x + w + 1, midY + HANDLE, 0xFFFFFFFF);
            for (int[] c : corners(r.x, r.y, w, h)) {
                context.fill(c[0] - HANDLE / 2, c[1] - HANDLE / 2, c[0] + HANDLE / 2, c[1] + HANDLE / 2, GOLD);
            }
        }
        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    /** HUD units -> GUI pixels for a working rect, whose scale may not be saved yet. */
    private static float scaleOf(SandboxWidget.PanelRect r) {
        return SandboxWidget.scaleFactor() * r.scalePercent() / 100f;
    }

    private static int[][] corners(int x, int y, int w, int h) {
        return new int[][] {{x, y}, {x + w, y}, {x, y + h}, {x + w, y + h}};
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent ctx, boolean doubleClick) {
        if (super.mouseClicked(ctx, doubleClick)) return true;
        int mouseX = (int) ctx.x();
        int mouseY = (int) ctx.y();
        // Last drawn is on top, so check in reverse.
        List<SandboxWidget.Panel> order = new ArrayList<>(rects.keySet());
        for (int i = order.size() - 1; i >= 0; i--) {
            SandboxWidget.Panel panel = order.get(i);
            SandboxWidget.PanelRect r = rects.get(panel);
            float scale = scaleOf(r);
            int w = Math.round(r.width * scale);
            int h = Math.round(r.height * scale);
            Handle handle = handleAt(mouseX, mouseY, r.x, r.y, w, h);
            if (handle != Handle.NONE || (mouseX >= r.x && mouseX <= r.x + w && mouseY >= r.y && mouseY <= r.y + h)) {
                active = panel;
                resizing = handle;
                dragging = handle == Handle.NONE;
                dragOffsetX = mouseX - r.x;
                dragOffsetY = mouseY - r.y;
                startMouseX = mouseX;
                startMouseY = mouseY;
                startRect = new SandboxWidget.PanelRect(r.x, r.y, r.width, r.height, r.scalePercent());
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent ctx, double deltaX, double deltaY) {
        if (active == null || (!dragging && resizing == Handle.NONE)) return super.mouseDragged(ctx, deltaX, deltaY);
        int mouseX = (int) ctx.x();
        int mouseY = (int) ctx.y();
        SandboxWidget.PanelRect r = rects.get(active);
        if (dragging) {
            r.x = Math.max(0, Math.min(width - 20, mouseX - dragOffsetX));
            r.y = Math.max(0, Math.min(height - 20, mouseY - dragOffsetY));
            return true;
        }
        float startScale = scaleOf(startRect);
        int startW = Math.max(1, Math.round(startRect.width * startScale));
        int startH = Math.max(1, Math.round(startRect.height * startScale));
        boolean left = resizing.left();
        boolean top = resizing.top();
        // Mouse movement in GUI pixels, positive = outward from the panel.
        int pixelDx = (mouseX - startMouseX) * (left ? -1 : 1);
        int pixelDy = (mouseY - startMouseY) * (top ? -1 : 1);
        if (resizing.isCorner()) {
            // Scale: grow by how far the corner moved on both axes, averaged, so the panel keeps its shape.
            float ratio = ((startW + pixelDx) / (float) startW + (startH + pixelDy) / (float) startH) / 2f;
            r.scale = SandboxWidget.clampPanelScale(Math.round(startRect.scalePercent() * ratio));
        } else if (resizing.horizontal()) {
            r.width = Math.max(SandboxWidget.minWidth(active), startRect.width + Math.round(pixelDx / startScale));
        } else {
            r.height = Math.max(SandboxWidget.minHeight(active), startRect.height + Math.round(pixelDy / startScale));
        }
        // Dragging a left/top handle moves that side, keeping the opposite one in place.
        float scale = scaleOf(r);
        r.x = left ? Math.max(0, startRect.x + startW - Math.round(r.width * scale)) : startRect.x;
        r.y = top ? Math.max(0, startRect.y + startH - Math.round(r.height * scale)) : startRect.y;
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent ctx) {
        if (active != null && (dragging || resizing != Handle.NONE)) {
            SandboxWidget.PanelRect r = rects.get(active);
            widget.setPanelRect(active, r.x, r.y, r.width, r.height, r.scalePercent());
            rects.put(active, widget.getPanelRect(active));
            dragging = false;
            resizing = Handle.NONE;
            return true;
        }
        return super.mouseReleased(ctx);
    }

    /** Corners win over edges; an edge can be grabbed anywhere along it. */
    private static Handle handleAt(int mouseX, int mouseY, int x, int y, int w, int h) {
        if (near(mouseX, mouseY, x, y)) return Handle.TOP_LEFT;
        if (near(mouseX, mouseY, x + w, y)) return Handle.TOP_RIGHT;
        if (near(mouseX, mouseY, x, y + h)) return Handle.BOTTOM_LEFT;
        if (near(mouseX, mouseY, x + w, y + h)) return Handle.BOTTOM_RIGHT;
        boolean inX = mouseX >= x && mouseX <= x + w;
        boolean inY = mouseY >= y && mouseY <= y + h;
        if (inX && Math.abs(mouseY - y) <= EDGE_GRAB) return Handle.TOP;
        if (inX && Math.abs(mouseY - (y + h)) <= EDGE_GRAB) return Handle.BOTTOM;
        if (inY && Math.abs(mouseX - x) <= EDGE_GRAB) return Handle.LEFT;
        if (inY && Math.abs(mouseX - (x + w)) <= EDGE_GRAB) return Handle.RIGHT;
        return Handle.NONE;
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
