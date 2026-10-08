package inventoryreader.ir;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/** "Move HUD": drag the HUD preview to move it, drag a corner to resize. Opened by B and from Settings. */
public class HudPositionScreen extends Screen {
    private final Screen parent;
    private final SandboxWidget widget;
    private static final int RECIPE_LEVEL_INDENT = 10;
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

    private int forestHeight() {
        int height = 0;
        for (RecipeManager.RecipeNode top : tops()) height += getExpandedNodeHeight(top, SandboxWidget.LIST_KEY);
        return height;
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
    context.fill(widgetPositionX, widgetPositionY, widgetPositionX + previewWidth, widgetPositionY + previewHeight, 0xFC271910);
        context.fill(widgetPositionX, widgetPositionY, widgetPositionX + previewWidth, widgetPositionY + 20, 0xCC2C4A1B);
        int borderColor = isDraggingWidget ? 0xFFFFDD00 : 0xFFDAA520;
        int borderThickness = 2;
        for (int i = 0; i < borderThickness; i++) {
            context.outline(widgetPositionX - i,
                widgetPositionY - i,
                previewWidth + i * 2,
                previewHeight + i * 2,
                borderColor
            );
        }
        String dragIcon = isDraggingWidget ? "✦" : "✥";
        drawFittedTextWithShadow(context,
            Component.literal("Shopping List " + dragIcon),
            widgetPositionX + 10,
            widgetPositionY + 6,
            GOLD,
            Math.max(10, previewWidth - 20)
        );
    if (!tops().isEmpty()) {
            {
                int contentX = widgetPositionX + 6;
                int contentY = widgetPositionY + 24;
                int contentWidth = Math.max(20, previewWidth - 20);
                int totalLines = forestHeight() / 16;
                totalLines = Math.max(totalLines, 1);
                int availableHeight = Math.max(10, previewHeight - (contentY - widgetPositionY) - 10);
                int lineHeight = Math.min(16, Math.max(6, availableHeight / totalLines));

                int maxDepth = 0;
                for (RecipeManager.RecipeNode top : tops()) maxDepth = Math.max(maxDepth, getExpandedMaxDepth(top, SandboxWidget.LIST_KEY, 0));
                int baseIndentUnit = Math.max(4, Math.round(RECIPE_LEVEL_INDENT * Math.max(0.3f, lineHeight / 16.0f)));
                int minNodeWidth = 60;
                int maxAllowedIndent = Math.max(2, (contentWidth - minNodeWidth) / Math.max(1, maxDepth));
                int indentUnit = Math.max(2, Math.min(baseIndentUnit, maxAllowedIndent));

                int treeEndY = contentY;
                for (RecipeManager.RecipeNode top : tops()) {
                    treeEndY = renderStaticWidgetStyleTreeScaled(context, top, contentX, treeEndY, 0, contentWidth, SandboxWidget.LIST_KEY, lineHeight, indentUnit);
                }

                int dividerY = treeEndY + 4;
                if (dividerY < widgetPositionY + previewHeight - 5) {
                    context.fill(widgetPositionX, dividerY, widgetPositionX + previewWidth, dividerY + 1, 0x99608C35);
                    int messageY = dividerY + 6;
                    int maxMessageY = widgetPositionY + previewHeight - 5;
                    drawCraftablePreview(context, widgetPositionX, messageY, previewWidth, maxMessageY);
                }
            }
        } else {
            drawFittedTextWithShadow(context,
                Component.literal("Shopping list is empty"),
                widgetPositionX + 10,
                widgetPositionY + 60,
                0xFFFFFFFF,
                Math.max(10, previewWidth - 20)
            );
        }
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

    private int renderStaticWidgetStyleTreeScaled(GuiGraphicsExtractor context, RecipeManager.RecipeNode node, int x, int y, int level, int availableWidth, String pathKey, int lineHeight, int indentUnit) {
        if (node == null) return y;
        int indent = level * indentUnit;
        boolean hasChildren = node.ingredients != null && !node.ingredients.isEmpty();
        boolean hasEnough = (node.amount <= 0);
        boolean showRemaining = widget.isShowRemaining();
        int nodeWidth = Math.max(40, availableWidth - indent);

        int bgColor = 0x99271910;
        context.fill(x + indent, y, x + indent + nodeWidth, y + lineHeight, bgColor);
        context.outline(x + indent, y, nodeWidth, lineHeight, SandboxWidget.progressBorderColor(node, showRemaining));

        String nextKey = SandboxWidget.makePathKey(pathKey, node.name);
        boolean isExpanded = widget.isNodeExpanded(nextKey);
        if (hasChildren) {
            context.text(
                font,
                isExpanded ? "▼" : "▶",
                x + indent + Math.max(3, Math.round(5 * (lineHeight / 16.0f))),
                y + Math.max(1, Math.round(4 * (lineHeight / 16.0f))),
                0xFFFFFFFF,
                false
            );
        }

        int nameX = x + indent + (hasChildren ? Math.max(14, Math.round(25 * (lineHeight / 16.0f))) : Math.max(6, Math.round(10 * (lineHeight / 16.0f))));
        int textColor = (level == 0) ? GOLD : (hasEnough ? 0xFFFFFFFF : SandboxWidget.progressColor(node, showRemaining));
        boolean isBold = (level == 0);

        String amountText = SandboxWidget.displayedAmount(node, showRemaining) + "× ";
        int amountColor = SandboxWidget.progressColor(node, showRemaining);
        int amountWidth = font.width(amountText);
        Component itemName = Component.literal(node.name).setStyle(Style.EMPTY.withColor(textColor).withBold(isBold));
        int itemWidth = font.width(itemName);
        int totalWidth = amountWidth + itemWidth;
        int maxTextWidth = Math.max(10, nodeWidth - (hasChildren ? Math.max(14, Math.round(25 * (lineHeight / 16.0f))) : Math.max(6, Math.round(10 * (lineHeight / 16.0f)))));
        int adjustedMax = (int)Math.floor(maxTextWidth / Math.max(0.01f, (lineHeight / 16.0f)));
        float textScaleLocal = totalWidth > adjustedMax ? (float)adjustedMax / (float)totalWidth : 1.0f;
        float textScale = Math.min(1.0f, textScaleLocal) * (lineHeight / 16.0f);
        context.pose().pushMatrix();
        context.pose().translate(nameX, y + Math.max(1, Math.round(4 * (lineHeight / 16.0f))));
        context.pose().scale(textScale, textScale);
        context.text(font, Component.literal(amountText), 0, 0, amountColor, false);
        context.text(font, itemName, amountWidth, 0, 0xFFFFFFFF, false);
        context.pose().popMatrix();

        y += lineHeight;

        if (hasChildren && isExpanded) {
            int lineColor = 0xFF777777;
            for (int i = 0; i < node.ingredients.size(); i++) {
                RecipeManager.RecipeNode child = node.ingredients.get(i);
                int lineStartX = x + indent + Math.max(4, Math.round(6 * (lineHeight / 16.0f)));
                int vertLineY = y;
                int childIndentX = x + indent + indentUnit;
                int vertLen = Math.max(3, Math.round(8 * (lineHeight / 16.0f)));
                context.fill(lineStartX, vertLineY, lineStartX + 1, vertLineY + vertLen, lineColor);
                context.fill(lineStartX, vertLineY + vertLen, childIndentX, vertLineY + vertLen + 1, lineColor);
                y = renderStaticWidgetStyleTreeScaled(context, child, x, y, level + 1, availableWidth, nextKey, lineHeight, indentUnit);
            }
        }
        return y;
    }

    private void drawCraftablePreview(GuiGraphicsExtractor context, int x, int y, int width, int maxY) {
        Minecraft client = Minecraft.getInstance();
        List<String> msgs = SandboxWidget.getInstance().getMessagesSnapshot();
        if (msgs == null || msgs.isEmpty()) {
            Component header = Component.literal("Craftable -").setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW).withBold(true));
            if (y + 10 <= maxY) context.text(client.font, header, x + 5, y, 0xFFFFFFFF, false);
            return;
        }
        int baseHeader = 13;
        int baseLine = 10;
        int availableHeight = Math.max(0, maxY - y);
        int lines = 1;
        for (String m : msgs) {
            if (!"Craftable -".equals(m)) lines++;
        }
        int desiredHeight = baseHeader + Math.max(0, (lines - 1) * baseLine);
        float scale = desiredHeight > 0 ? Math.min(1.0f, Math.max(0.4f, (float)availableHeight / (float)desiredHeight)) : 1.0f;

        if (y + Math.round(baseLine * scale) <= maxY) {
            Component header = Component.literal("Craftable -").setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW).withBold(true));
            context.pose().pushMatrix();
            context.pose().translate(x + (width - client.font.width(header) * scale) / 2f, y);
            context.pose().scale(scale, scale);
            context.text(client.font, header, 0, 0, 0xFFFFFFFF, false);
            context.pose().popMatrix();
        } else {
            return;
        }
        if (msgs.size() == 1 && msgs.get(0).equals("Craftable -")) return;
        y += Math.round(baseHeader * scale);

        List<String> messagesCopy = new ArrayList<>(msgs);
        messagesCopy.sort((a, b) -> {
            if (a.equals("Craftable -")) return -1;
            if (b.equals("Craftable -")) return 1;
            try {
                int amountA = extractAmount(a);
                int amountB = extractAmount(b);
                return Integer.compare(amountB, amountA);
            } catch (Exception e) { return 0; }
        });
        int unscaledWrapWidth = Math.max(10, (int)Math.floor((width - 15) / Math.max(0.01f, scale)));
        for (String message : messagesCopy) {
            if (message.equals("Craftable -")) continue;
            int textColor = message.startsWith("   ") ? 0xFFFF9D00 : 0xFFFFFFFF;
            String[] words = message.split(" ");
            StringBuilder line = new StringBuilder();
            for (String word : words) {
                if (client.font.width(line.toString() + word) > unscaledWrapWidth) {
                    if (y + Math.round(baseLine * scale) > maxY) return;
                    context.pose().pushMatrix();
                    String centred = line.toString().trim();
                    context.pose().translate(x + (width - client.font.width(centred) * scale) / 2f, y);
                    context.pose().scale(scale, scale);
                    context.text(client.font, centred, 0, 0, textColor, false);
                    context.pose().popMatrix();
                    y += Math.round(baseLine * scale);
                    line = new StringBuilder(message.startsWith("   ") ? "      " : "   ").append(word).append(" ");
                } else {
                    line.append(word).append(" ");
                }
            }
            if (line.length() > 0) {
                if (y + Math.round((baseLine - 1) * scale) > maxY) return;
                context.pose().pushMatrix();
                String centred = line.toString().trim();
                context.pose().translate(x + (width - client.font.width(centred) * scale) / 2f, y);
                context.pose().scale(scale, scale);
                context.text(client.font, centred, 0, 0, textColor, false);
                context.pose().popMatrix();
                y += Math.round((baseLine - 1) * scale);
            }
        }
    }

    private int extractAmount(String message) {
        try {
            int idx = message.indexOf('×');
            if (idx > 0) {
                String amt = message.substring(0, idx).trim();
                return Integer.parseInt(amt);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private int getExpandedMaxDepth(RecipeManager.RecipeNode node, String pathKey, int level) {
        if (node == null) return level;
        boolean hasChildren = node.ingredients != null && !node.ingredients.isEmpty();
        String nextKey = SandboxWidget.makePathKey(pathKey, node.name);
        boolean isExpanded = widget.isNodeExpanded(nextKey);
        int max = level;
        if (hasChildren && isExpanded) {
            for (RecipeManager.RecipeNode child : node.ingredients) {
                max = Math.max(max, getExpandedMaxDepth(child, nextKey, level + 1));
            }
        }
        return max;
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

    private int getExpandedNodeHeight(RecipeManager.RecipeNode node, String pathKey) {
        if (node == null) return 0;
        int height = 16;
        if (node.ingredients != null && !node.ingredients.isEmpty() &&
        widget.isNodeExpanded(SandboxWidget.makePathKey(pathKey, node.name))) {
        for (RecipeManager.RecipeNode child : node.ingredients) {
        height += getExpandedNodeHeight(child, SandboxWidget.makePathKey(pathKey, node.name));
            }
        }
        return height;
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
