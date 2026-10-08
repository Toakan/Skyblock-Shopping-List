package inventoryreader.ir;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** "Move HUD": drag the HUD preview to move it, drag a corner to resize. Opened by B and from Settings. */
public class HudPositionScreen extends Screen {
    private final Screen parent;
    private final SandboxWidget widget;
    private static final int GOLD = 0xFFFFB728;
    private int widgetPositionX, widgetPositionY;
    private boolean isDraggingWidget = false;
    private int dragOffsetX = 0;
    private int dragOffsetY = 0;
    private boolean resizing = false;
    private int resizeStartX, resizeStartY;
    private int initialWidth, initialHeight;
    private int initialWidgetX, initialWidgetY;
    private int previewWidthOverride = -1;
    private int previewHeightOverride = -1;
    private enum ResizeCorner { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    private ResizeCorner activeCorner = ResizeCorner.NONE;
    private static final int RESIZE_HANDLE_SIZE = 10;

    /** @param parent screen to return to on Done, or null to close. */
    public HudPositionScreen(Screen parent) {
        super(Component.literal("Move HUD"));
        this.parent = parent;
        this.widget = SandboxWidget.getInstance();
        this.widgetPositionX = widget.getWidgetX();
        this.widgetPositionY = widget.getWidgetY();
    }

    @Override
    protected void init() {
        initPositioningTab();
    }

    private void initPositioningTab() {
        int buttonWidth = 90;
        int buttonHeight = 20;
        int y = 1;
        int spacing = 4;

        addRenderableWidget(Button.builder(Component.literal("Reset position"), button -> {
            widgetPositionX = 10;
            widgetPositionY = 40;
            widget.setWidgetPosition(widgetPositionX, widgetPositionY);
        }).bounds(width - 2 * buttonWidth - spacing - 4, y, buttonWidth, buttonHeight).build());

        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
            .bounds(width - buttonWidth - 4, y, buttonWidth, buttonHeight).build());
    }

    /** Top-level nodes the HUD shows: Total, then one tree per recipe. */
    private List<RecipeManager.RecipeNode> tops() {
        RecipeManager.RecipeNode root = widget.getDisplayRoot();
        return root == null || root.ingredients == null ? List.of() : root.ingredients;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        renderPositioningTab(context, mouseX, mouseY);
        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    private void renderPositioningTab(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        context.fill(0, 0, width, height, 0xFF0E0E0E);
        context.outline(0, 0, width, height, 0x88608C35);

        context.fill(0, 0, width, 22, 0xFF17293A);
        context.outline(0, 0, width, 22, 0xFF223344);
        String modTitle = "Move HUD";
        context.text(font, modTitle, width / 2 - font.width(modTitle) / 2, 7, GOLD, false);

        context.fill(0, 22, width, 44, 0xFF131313);

        String posTitle = "Widget Positioning";
        context.text(font, posTitle, width / 2 - font.width(posTitle) / 2, 52, 0xFFE0E0E0, false);

        String hint = "Drag the widget preview to reposition   |   Drag corner handles to resize";
        context.text(font, hint, width / 2 - font.width(hint) / 2, 65, 0xFFBBBBBB, false);

        String posInfo = "X=" + widgetPositionX + "  Y=" + widgetPositionY
            + "  W=" + getPreviewWidth() + "  H=" + getPreviewHeight();
        context.text(font, posInfo, width / 2 - font.width(posInfo) / 2, 78, GOLD, false);
    int previewWidth = getPreviewWidth();
    int previewHeight = getPreviewHeight();
        for (int x = 0; x < width; x += 50) {
            context.fill(x, 0, x + 1, height, 0x22FFFFFF);
        }
        for (int y = 0; y < height; y += 50) {
            context.fill(0, y, width, y + 1, 0x22FFFFFF);
        }
        if (tops().isEmpty()) {
            context.fill(widgetPositionX, widgetPositionY, widgetPositionX + previewWidth, widgetPositionY + previewHeight, 0xCC271910);
            drawFittedTextWithShadow(context,
                Component.literal("Shopping list is empty"),
                widgetPositionX + 10,
                widgetPositionY + 60,
                0xFFFFFFFF,
                Math.max(10, previewWidth - 20)
            );
        } else {
            // Opaque backing so the hint text behind doesn't show through a see-through panel colour.
            context.fill(widgetPositionX, widgetPositionY, widgetPositionX + previewWidth, widgetPositionY + previewHeight, 0xFF0E0E0E);
            // The real HUD renderer, so the preview shows the current Appearance settings exactly.
            widget.renderAt(context, widgetPositionX, widgetPositionY, previewWidth, previewHeight);
        }
        // The area the HUD may grow into.
        context.outline(widgetPositionX, widgetPositionY, previewWidth, previewHeight,
            isDraggingWidget || resizing ? 0xFFFFDD00 : 0x88FFFFFF);
        if (isDraggingWidget || resizing) {
            String hint2 = resizing ? "Release to resize" : "Release to place widget";
            context.text(font, hint2, widgetPositionX + previewWidth / 2 - font.width(hint2) / 2, widgetPositionY + previewHeight - 15, 0xFFAAAAFF, false);
        } else {
            String hint2 = "Drag to reposition";
            context.text(font, hint2, widgetPositionX + previewWidth / 2 - font.width(hint2) / 2, widgetPositionY + previewHeight - 15, 0xFF888888, false);
        }

    drawHandle(context, widgetPositionX - RESIZE_HANDLE_SIZE/2, widgetPositionY - RESIZE_HANDLE_SIZE/2);
    drawHandle(context, widgetPositionX + previewWidth - RESIZE_HANDLE_SIZE/2, widgetPositionY - RESIZE_HANDLE_SIZE/2);
    drawHandle(context, widgetPositionX - RESIZE_HANDLE_SIZE/2, widgetPositionY + previewHeight - RESIZE_HANDLE_SIZE/2);
    drawHandle(context, widgetPositionX + previewWidth - RESIZE_HANDLE_SIZE/2, widgetPositionY + previewHeight - RESIZE_HANDLE_SIZE/2);
    }

    private void drawHandle(GuiGraphicsExtractor context, int x, int y) {
        context.fill(x, y, x + RESIZE_HANDLE_SIZE, y + RESIZE_HANDLE_SIZE, 0xFFFFB728);
    }

    private void drawFittedTextWithShadow(GuiGraphicsExtractor context, Component text, int x, int y, int color, int maxWidth) {
        int width = font.width(text);
        float scale = width > maxWidth ? (float)maxWidth / (float)width : 1.0f;
        scale = Math.min(scale, getMaxTextScale());
        context.pose().pushMatrix();
        context.pose().translate(x, y);
        context.pose().scale(scale, scale);
        context.text(font, text, 0, 0, color);
        context.pose().popMatrix();
    }

    private float getMaxTextScale() {
        return 1.0f;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent ctx, boolean doubleClick) {
        double mouseX = ctx.x();
        double mouseY = ctx.y();
        int previewWidth = getPreviewWidth();
        int previewHeight = getPreviewHeight();
        ResizeCorner corner = getCornerHandle(mouseX, mouseY, previewWidth, previewHeight);
        if (corner != ResizeCorner.NONE) {
            resizing = true;
            activeCorner = corner;
            resizeStartX = (int) mouseX;
            resizeStartY = (int) mouseY;
            initialWidth = previewWidth;
            initialHeight = previewHeight;
            initialWidgetX = widgetPositionX;
            initialWidgetY = widgetPositionY;
            return true;
        }
        if (mouseX >= widgetPositionX && mouseX <= widgetPositionX + previewWidth &&
            mouseY >= widgetPositionY && mouseY <= widgetPositionY + previewHeight) {
            isDraggingWidget = true;
            dragOffsetX = (int) mouseX - widgetPositionX;
            dragOffsetY = (int) mouseY - widgetPositionY;
            return true;
        }
        return super.mouseClicked(ctx, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent ctx, double deltaX, double deltaY) {
        double mouseX = ctx.x();
        double mouseY = ctx.y();
        if (isDraggingWidget) {
            widgetPositionX = (int) mouseX - dragOffsetX;
            widgetPositionY = (int) mouseY - dragOffsetY;
            widgetPositionX = Math.max(0, Math.min(width - 50, widgetPositionX));
            widgetPositionY = Math.max(0, Math.min(height - 50, widgetPositionY));
            return true;
        } else if (resizing) {
            int dx = (int) mouseX - resizeStartX;
            int dy = (int) mouseY - resizeStartY;
            int newWidth = initialWidth;
            int newHeight = initialHeight;
            int newX = initialWidgetX;
            int newY = initialWidgetY;
            int minW = 180;
            int minH = 120;
            switch (activeCorner) {
                case TOP_LEFT -> {
                    newWidth = initialWidth - dx;
                    newHeight = initialHeight - dy;
                    newX = initialWidgetX + dx;
                    newY = initialWidgetY + dy;
                    if (newWidth < minW) {
                        newX = initialWidgetX + (initialWidth - minW);
                        newWidth = minW;
                    }
                    if (newHeight < minH) {
                        newY = initialWidgetY + (initialHeight - minH);
                        newHeight = minH;
                    }
                }
                case TOP_RIGHT -> {
                    newWidth = initialWidth + dx;
                    newHeight = initialHeight - dy;
                    newY = initialWidgetY + dy;
                    if (newWidth < minW) {
                        newWidth = minW;
                    }
                    if (newHeight < minH) {
                        newY = initialWidgetY + (initialHeight - minH);
                        newHeight = minH;
                    }
                }
                case BOTTOM_LEFT -> {
                    newWidth = initialWidth - dx;
                    newHeight = initialHeight + dy;
                    newX = initialWidgetX + dx;
                    if (newWidth < minW) {
                        newX = initialWidgetX + (initialWidth - minW);
                        newWidth = minW;
                    }
                    if (newHeight < minH) {
                        newHeight = minH;
                    }
                }
                case BOTTOM_RIGHT -> {
                    newWidth = initialWidth + dx;
                    newHeight = initialHeight + dy;
                    if (newWidth < minW) newWidth = minW;
                    if (newHeight < minH) newHeight = minH;
                }
                case NONE -> {

                }
            }
            widgetPositionX = Math.max(0, newX);
            widgetPositionY = Math.max(0, newY);
            previewWidthOverride = newWidth;
            previewHeightOverride = newHeight;
            return true;
        }
        return super.mouseDragged(ctx, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent ctx) {
        if (isDraggingWidget || resizing) {
            isDraggingWidget = false;
            resizing = false;
            activeCorner = ResizeCorner.NONE;
            int commitW = getPreviewWidth();
            int commitH = getPreviewHeight();
            widget.setWidgetSize(commitW, commitH);
            widget.setWidgetPosition(widgetPositionX, widgetPositionY);
            widget.saveConfiguration();
            previewWidthOverride = -1;
            previewHeightOverride = -1;
            return true;
        }
        return super.mouseReleased(ctx);
    }

    private int getPreviewWidth() {
        return previewWidthOverride > 0 ? previewWidthOverride : widget.getWidgetWidth();
    }
    private int getPreviewHeight() {
        return previewHeightOverride > 0 ? previewHeightOverride : widget.getWidgetHeight();
    }

    private ResizeCorner getCornerHandle(double mouseX, double mouseY, int previewWidth, int previewHeight) {
        if (isInHandle(mouseX, mouseY, widgetPositionX, widgetPositionY)) return ResizeCorner.TOP_LEFT;
        if (isInHandle(mouseX, mouseY, widgetPositionX + previewWidth, widgetPositionY)) return ResizeCorner.TOP_RIGHT;
        if (isInHandle(mouseX, mouseY, widgetPositionX, widgetPositionY + previewHeight)) return ResizeCorner.BOTTOM_LEFT;
        if (isInHandle(mouseX, mouseY, widgetPositionX + previewWidth, widgetPositionY + previewHeight)) return ResizeCorner.BOTTOM_RIGHT;
        return ResizeCorner.NONE;
    }
    private boolean isInHandle(double mouseX, double mouseY, int cx, int cy) {
        int x = cx - RESIZE_HANDLE_SIZE/2;
        int y = cy - RESIZE_HANDLE_SIZE/2;
        return mouseX >= x && mouseX <= x + RESIZE_HANDLE_SIZE && mouseY >= y && mouseY <= y + RESIZE_HANDLE_SIZE;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        widget.setWidgetPosition(widgetPositionX, widgetPositionY);
        this.minecraft.gui.setScreen(parent);
    }
}
