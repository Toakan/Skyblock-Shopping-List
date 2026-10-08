package inventoryreader.ir;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

public class WidgetCustomizationMenu extends Screen {
    private enum Tab {
        RECIPE_SELECTION,
        POSITIONING
    }
    private final SandboxWidget widget;
    private final RecipeManager recipeManager;
    private final ResourcesManager resourcesManager;
    private EditBox searchField;
    private List<String> filteredRecipes;
    private int scrollOffset = 0;
    private final int MAX_RECIPES_SHOWN = 10;
    private String searchText = "";
    /** Recipe names the shopping-list rows were built for; rows are rebuilt when the list changes. */
    private List<String> builtRows = List.of();
    private static final int PANEL_TOP = 58;
    private static final int PANEL_ROW = 22;
    private static final int PANEL_MAX_ROWS = 5;
    private int treeViewX = 300;
    private int treeViewY = 80;
    private int treeViewWidth = 400;
    private int treeViewHeight = 300;
    private int treeScrollOffset = 0;
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
    private Tab currentTab = Tab.RECIPE_SELECTION;
    private Button recipeTabButton;
    private Button positioningTabButton;

    public WidgetCustomizationMenu() {
        super(Component.literal("Widget Customization"));
        this.widget = SandboxWidget.getInstance();
        this.recipeManager = RecipeManager.getInstance();
        this.resourcesManager = ResourcesManager.getInstance();
        this.filteredRecipes = new ArrayList<>(recipeManager.getRecipeNames());
        this.widgetPositionX = widget.getWidgetX();
        this.widgetPositionY = widget.getWidgetY();
        this.currentTab = Tab.RECIPE_SELECTION;
    }

    public WidgetCustomizationMenu(boolean openPositioningTab) {
        this();
        if (openPositioningTab) {
            this.currentTab = Tab.POSITIONING;
        }
    }

    @Override
    protected void init() {
        super.init();
        recipeTabButton = Button.builder(
            Component.literal("Recipe Selection"),
            button -> switchTab(Tab.RECIPE_SELECTION)
        )
        .bounds(20, 24, 150, 18)
        .build();
        positioningTabButton = Button.builder(
            Component.literal("Widget Positioning"),
            button -> switchTab(Tab.POSITIONING)
        )
        .bounds(180, 24, 150, 18)
        .build();
        addRenderableWidget(recipeTabButton);
        addRenderableWidget(positioningTabButton);
        updateTabButtonStyles();
        if (currentTab == Tab.RECIPE_SELECTION) {
            initRecipeTab();

            Button toggleButton = Button.builder(
                widget.isEnabled() ? Component.literal("Disable Widget") : Component.literal("Enable Widget"),
                button -> {
                    boolean newState = !widget.isEnabled();
                    widget.setEnabled(newState);
                    button.setMessage(newState ? Component.literal("Disable Widget") : Component.literal("Enable Widget"));

                    Minecraft client = Minecraft.getInstance();
                    if (client.player != null) {
                        Component message = Component.literal("Widget " + (newState ? "enabled!" : "disabled!"))
                            .setStyle(Style.EMPTY.withColor(newState ? ChatFormatting.GREEN : ChatFormatting.RED));
                        client.player.sendOverlayMessage(message);
                    }
                }
            )
            .bounds(20, height - 30, 150, 20)
            .build();
            addRenderableWidget(toggleButton);
        } else {
            initPositioningTab();
        }
        if (currentTab != Tab.POSITIONING) {
            Button saveButton = Button.builder(
                Component.literal("Save Configuration"),
                button -> saveWidgetConfiguration()
            )
            .bounds(width - 170, height - 30, 150, 20)
            .build();
            addRenderableWidget(saveButton);
        }
    }

    private void initRecipeTab() {
        searchField = new EditBox(font, 20, 55, 250, 20, Component.literal(""));
        searchField.setMaxLength(50);
        searchField.setHint(Component.literal("Search recipes..."));
        searchField.setValue(searchText);
        searchField.setResponder(this::updateFilteredRecipes);
        addRenderableWidget(searchField);

        // Shopping list panel (right column, above the preview): name, amount box, remove button per row.
        List<ShoppingListEntry> list = widget.getShoppingList();
        treeViewWidth = Math.max(200, Math.min(400, width - treeViewX - 20));
        int rows = Math.min(list.size(), PANEL_MAX_ROWS);
        for (int i = 0; i < rows; i++) {
            ShoppingListEntry entry = list.get(i);
            int y = PANEL_TOP + i * PANEL_ROW;
            EditBox amount = new EditBox(font, treeViewX + treeViewWidth - 70, y, 40, 18, Component.literal("Amount"));
            amount.setMaxLength(6);
            amount.setValue(String.valueOf(entry.amount));
            amount.setResponder(text -> {
                try {
                    int value = Integer.parseInt(text.trim());
                    if (value > 0) widget.setEntryAmount(entry.recipe, value);
                } catch (NumberFormatException ignored) {
                    // Keep the saved amount while the box is empty or half-typed.
                }
            });
            addRenderableWidget(amount);
            addRenderableWidget(Button.builder(Component.literal("×"), button -> {
                widget.removeFromList(entry.recipe);
                rebuildWidgets();
            }).bounds(treeViewX + treeViewWidth - 26, y, 20, 18).build());
        }
        builtRows = list.stream().map(e -> e.recipe).toList();
        treeViewY = PANEL_TOP + Math.max(1, rows) * PANEL_ROW + 22;
        treeViewHeight = Math.max(60, height - 40 - treeViewY);

        addRenderableWidget(Button.builder(Component.literal("Clear list"), button -> {
            widget.clearList();
            rebuildWidgets();
        }).bounds(width / 2 - 60, height - 30, 120, 20).build());
    }

    @Override
    public void tick() {
        super.tick();
        // Entries can disappear on their own (auto-remove); keep the rows in step with the list.
        if (currentTab == Tab.RECIPE_SELECTION) {
            List<String> names = widget.getShoppingList().stream().map(e -> e.recipe).toList();
            if (!names.equals(builtRows)) rebuildWidgets();
        }
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

    private void initPositioningTab() {
        int buttonWidth = 100;
        int buttonHeight = 20;
        int bottomMargin = 30;
        int y = height - bottomMargin;
        int spacing = 10;

        Button applyButton = Button.builder(
            Component.literal("Apply Configuration"),
            button -> applyConfiguration()
        )
        .bounds(width / 2 - buttonWidth - spacing/2, y, buttonWidth, buttonHeight)
        .build();
        addRenderableWidget(applyButton);

        Button resetButton = Button.builder(
            Component.literal("Reset Position"),
            button -> {
                widgetPositionX = 10;
                widgetPositionY = 40;
                widget.setWidgetPosition(widgetPositionX, widgetPositionY);
                Minecraft client = Minecraft.getInstance();
                if (client.player != null) {
                    Component message = Component.literal("Widget position reset!")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN));
                    client.player.sendOverlayMessage(message);
                }
            }
        )
        .bounds(width / 2 + spacing/2, y, buttonWidth, buttonHeight)
        .build();
        addRenderableWidget(resetButton);
    }

    private void switchTab(Tab newTab) {
        currentTab = newTab;
        clearWidgets();
        init();
    }

    private void updateTabButtonStyles() {
        if (recipeTabButton != null && positioningTabButton != null) {
            recipeTabButton.active = currentTab != Tab.RECIPE_SELECTION;
            positioningTabButton.active = currentTab != Tab.POSITIONING;
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {

    }

    private void updateFilteredRecipes(String searchTerm) {
        searchText = searchTerm == null ? "" : searchTerm;
        if (searchTerm == null || searchTerm.isEmpty()) {
            this.filteredRecipes = new ArrayList<>(recipeManager.getRecipeNames());
        } else {
            String lowerSearchTerm = searchTerm.toLowerCase();
            this.filteredRecipes = recipeManager.getRecipeNames().stream()
                .filter(name -> name.toLowerCase().contains(lowerSearchTerm))
                .collect(Collectors.toList());
        }
        scrollOffset = 0;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        if (currentTab == Tab.RECIPE_SELECTION) {
            renderRecipeTab(context, mouseX, mouseY);
        } else {
            renderPositioningTab(context, mouseX, mouseY);
        }
        int activeTabX = currentTab == Tab.RECIPE_SELECTION ? 20 : 180;
        context.fill(activeTabX, 42, activeTabX + 150, 44, 0xFF5FAF3F);
        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    private void renderRecipeTab(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        context.fill(0, 0, width, height, 0xFF0E0E0E);
        context.outline(0, 0, width, height, 0x88608C35);

        context.fill(0, 0, width, 22, 0xFF17293A);
        context.outline(0, 0, width, 22, 0xFF223344);
        String modTitle = "Widget Customization";
        context.text(font, modTitle, width / 2 - font.width(modTitle) / 2, 7, GOLD, false);

        context.fill(0, 22, width, 44, 0xFF131313);

        context.text(font,
            Component.literal("Recipe Selection").setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true)),
            20, 47, GOLD, false);
        renderListPanel(context);
        context.text(font,
            Component.literal("Preview").setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true)),
            treeViewX, treeViewY - 13, GOLD, false);
        int yPos = 85;
        int itemHeight = 20;
        int endIndex = Math.min(scrollOffset + MAX_RECIPES_SHOWN, filteredRecipes.size());
        java.util.Set<String> inList = new java.util.HashSet<>(builtRows);

        context.fill(20, yPos - 5, 270, yPos + MAX_RECIPES_SHOWN * itemHeight + 15, 0xFC271910);

        int listBorderColor = 0xFFDAA520;
        for (int i = 0; i < 2; i++) {
            context.outline(20 - i,
                yPos - 5 - i,
                250 + i * 2,
                MAX_RECIPES_SHOWN * itemHeight + 20 + i * 2,
                listBorderColor
            );
        }
        if (filteredRecipes.isEmpty()) {
            context.text(font, "No recipes found", 30, yPos + 10, 0xFFAAAAAA, false);
        } else {
            for (int i = scrollOffset; i < endIndex; i++) {
                String recipe = filteredRecipes.get(i);
                boolean isSelected = inList.contains(recipe);
                if (isSelected) {
                    context.fill(20, yPos, 270, yPos + itemHeight, 0x99608C35);
                    context.text(font, recipe, 30, yPos + 5, 0xFFFFB728, false);
                } else {
                    boolean isHovered = mouseX >= 20 && mouseX <= 270 && mouseY >= yPos && mouseY <= yPos + itemHeight;
                    if (isHovered) {
                        context.fill(20, yPos, 270, yPos + itemHeight, 0x553E6428);
                    }
                    context.text(font, recipe, 30, yPos + 5, 0xFFE0E0E0, false);
                }
                yPos += itemHeight;
            }
            if (scrollOffset > 0) {
                String up = "▲";
                context.text(font, up, 145 - font.width(up) / 2, 75, GOLD, false);
            }
            if (endIndex < filteredRecipes.size()) {
                String down = "▼";
                context.text(font, down, 145 - font.width(down) / 2, yPos + 5, GOLD, false);
            }
        }

        context.fill(treeViewX, treeViewY, treeViewX + treeViewWidth, treeViewY + treeViewHeight, 0xFC271910);

        int treeBorderColor = 0xFFDAA520;
        int borderThickness = 2;
        for (int i = 0; i < borderThickness; i++) {
            context.outline(treeViewX - i,
                treeViewY - i,
                treeViewWidth + i * 2,
                treeViewHeight + i * 2,
                treeBorderColor
            );
        }

        context.enableScissor(
            treeViewX,
            treeViewY,
            treeViewX + treeViewWidth,
            treeViewY + treeViewHeight
        );
        if (!tops().isEmpty()) {
            int treeY = treeViewY + 10 - treeScrollOffset;
            for (RecipeManager.RecipeNode top : tops()) {
                treeY = renderRecipeTree(context, top, treeViewX + 10, treeY, 0, SandboxWidget.LIST_KEY);
            }
            int totalHeight = forestHeight();
            if (totalHeight > treeViewHeight) {
                if (treeScrollOffset > 0) {
                    String up = "▲";
                    context.text(font, up, treeViewX + treeViewWidth - 15 - font.width(up) / 2, treeViewY + 15, GOLD, false);
                }
                if (treeScrollOffset < totalHeight - treeViewHeight + 20) {
                    String down = "▼";
                    context.text(font, down, treeViewX + treeViewWidth - 15 - font.width(down) / 2, treeViewY + treeViewHeight - 15, GOLD, false);
                }
                int scrollbarWidth = 12;
                int scrollbarHeight = Math.max(40, treeViewHeight * treeViewHeight / totalHeight);
                int scrollbarY = treeViewY + (int)((treeViewHeight - scrollbarHeight) * ((float)treeScrollOffset / (totalHeight - treeViewHeight + 20)));
                context.fill(treeViewX + treeViewWidth - scrollbarWidth - 4, treeViewY, treeViewX + treeViewWidth - 4, treeViewY + treeViewHeight, 0x55FFFFFF);
                context.fill(treeViewX + treeViewWidth - scrollbarWidth - 4, scrollbarY, treeViewX + treeViewWidth - 4, scrollbarY + scrollbarHeight, 0xFFDAA520);
            }
        } else if (!builtRows.isEmpty()) {
            context.text(font, "Working out the list...", treeViewX + 20, treeViewY + 20, 0xFFAAAAAA, false);
        } else {
            context.text(font, "Click recipes on the left to add them", treeViewX + 20, treeViewY + 20, 0xFFAAAAAA, false);
        }
        context.disableScissor();
    }

    private void renderListPanel(GuiGraphicsExtractor context) {
        List<ShoppingListEntry> list = widget.getShoppingList();
        String header = "Shopping list (" + list.size() + "/" + widget.getMaxRecipes() + ")";
        context.text(font, Component.literal(header).setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true)),
            treeViewX, 47, GOLD, false);
        int rows = Math.min(list.size(), PANEL_MAX_ROWS);
        if (rows == 0) {
            context.text(font, "Empty - click a recipe on the left to add it", treeViewX + 4, PANEL_TOP + 5, 0xFFAAAAAA, false);
            return;
        }
        for (int i = 0; i < rows; i++) {
            int y = PANEL_TOP + i * PANEL_ROW;
            context.fill(treeViewX, y - 2, treeViewX + treeViewWidth, y + 20, i % 2 == 0 ? 0xFC271910 : 0xFC2E1F14);
            String name = font.plainSubstrByWidth(list.get(i).recipe, treeViewWidth - 80);
            context.text(font, name, treeViewX + 4, y + 5, 0xFFE0E0E0, false);
        }
        if (list.size() > rows) {
            context.text(font, "+" + (list.size() - rows) + " more", treeViewX + 4, PANEL_TOP + rows * PANEL_ROW, 0xFFAAAAAA, false);
        }
    }

    private void renderPositioningTab(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        context.fill(0, 0, width, height, 0xFF0E0E0E);
        context.outline(0, 0, width, height, 0x88608C35);

        context.fill(0, 0, width, 22, 0xFF17293A);
        context.outline(0, 0, width, 22, 0xFF223344);
        String modTitle = "Widget Customization";
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
            Component.literal("Recipe Widget " + dragIcon),
            widgetPositionX + 10,
            widgetPositionY + 6,
            GOLD,
            Math.max(10, previewWidth - 20)
        );
    if (!tops().isEmpty()) {
            drawFittedTextWithShadow(context,
                Component.literal(widget.getTitle()),
                widgetPositionX + 10,
                widgetPositionY + 30,
                GOLD,
                Math.max(10, previewWidth - 20)
            );
            {
                int contentX = widgetPositionX + 10;
                int contentY = widgetPositionY + 50;
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



    private int renderRecipeTree(GuiGraphicsExtractor context, RecipeManager.RecipeNode node, int x, int y, int level, String pathKey) {
        if (node == null) return y;
        Minecraft client = Minecraft.getInstance();
        int indent = level * RECIPE_LEVEL_INDENT;
        boolean hasEnough = (node.amount <= 0);
        boolean showRemaining = widget.isShowRemaining();
        String nodeKey = SandboxWidget.makePathKey(pathKey, node.name);
        boolean isExpanded = widget.isNodeExpanded(nodeKey);
        boolean hasChildren = node.ingredients != null && !node.ingredients.isEmpty();
        int bgColor = 0x99271910;

        int mouseX = (int)(client.mouseHandler.xpos() / client.getWindow().getGuiScale());
        int mouseY = (int)(client.mouseHandler.ypos() / client.getWindow().getGuiScale());
        boolean isHovered = mouseX >= x + indent && mouseX <= x + indent + (treeViewWidth - 20 - indent) &&
                           mouseY >= y && mouseY <= y + 16;
        int nodeWidth = treeViewWidth - 20 - indent;
        context.fill(x + indent, y, x + indent + nodeWidth, y + 16, bgColor);
        if (isHovered) {
            context.fill(x + indent, y, x + indent + nodeWidth, y + 16, 0x22FFFFFF);
        }

        context.outline(x + indent, y, nodeWidth, 16, SandboxWidget.progressBorderColor(node, showRemaining));

        if (hasChildren) {
            String expandIcon = isExpanded ? "▼" : "▶";
            context.text(
                client.font,
                expandIcon,
                x + indent + 5,
                y + 4,
                0xFFFFFFFF,
                false
            );
        }

        int nameX = x + indent + (hasChildren ? 25 : 10);

        int textColor;
        boolean isBold = (level == 0);
        if (level == 0) {
            textColor = GOLD;
        } else {
            textColor = hasEnough ? 0xFFFFFFFF : SandboxWidget.progressColor(node, showRemaining);
        }

        String prefix = "";
        String amountText = SandboxWidget.displayedAmount(node, showRemaining) + "×";
        int amountColor = SandboxWidget.progressColor(node, showRemaining);

        context.text(
            client.font,
            prefix,
            nameX,
            y + 4,
            0xFFFFFFFF,
            false
        );

        context.text(
            client.font,
            amountText,
            nameX + client.font.width(prefix),
            y + 4,
            amountColor,
            false
        );

        Component itemName = Component.literal(node.name)
            .setStyle(Style.EMPTY.withColor(textColor)
            .withBold(isBold));

        context.text(
            client.font,
            itemName,
            nameX + client.font.width(prefix + amountText + " "),
            y + 4,
            0xFFFFFFFF,
            false
        );

        y += 16;

    if (hasChildren && isExpanded && node.ingredients.size() > 0) {
            int lineColor = 0xFF777777;
            for (int i = 0; i < node.ingredients.size(); i++) {
                RecipeManager.RecipeNode child = node.ingredients.get(i);
                int lineStartX = x + indent + 6;
                int vertLineY = y;
                int childIndentX = x + indent + RECIPE_LEVEL_INDENT;

                context.fill(lineStartX, vertLineY, lineStartX + 1, vertLineY + 8, lineColor);
                context.fill(lineStartX, vertLineY + 8, childIndentX, vertLineY + 9, lineColor);

                y = renderRecipeTree(context, child, x, y, level + 1, nodeKey);
            }
        }
        return y;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (currentTab == Tab.RECIPE_SELECTION && mouseX >= 20 && mouseX <= 270 && mouseY >= 80) {
            // Wheel down (negative) moves the list down, matching the tree view and vanilla lists.
            if (verticalAmount > 0 && scrollOffset > 0) {
                scrollOffset--;
                return true;
            } else if (verticalAmount < 0 && scrollOffset + MAX_RECIPES_SHOWN < filteredRecipes.size()) {
                scrollOffset++;
                return true;
            }
        }
        if (currentTab == Tab.RECIPE_SELECTION &&
            mouseX >= treeViewX && mouseX <= treeViewX + treeViewWidth &&
            mouseY >= treeViewY && mouseY <= treeViewY + treeViewHeight) {
            if (verticalAmount != 0 && !tops().isEmpty()) {
                int totalTreeHeight = forestHeight();
                int visibleHeight = treeViewHeight - 20;
                int scrollAmount = (int)(verticalAmount * -12);
                treeScrollOffset += scrollAmount;
                int maxScroll = Math.max(0, totalTreeHeight - visibleHeight);
                treeScrollOffset = Math.max(0, Math.min(treeScrollOffset, maxScroll));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent ctx, boolean doubleClick) {
        double mouseX = ctx.x();
        double mouseY = ctx.y();
        int button = ctx.button();
        if (currentTab == Tab.POSITIONING) {
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
        if (currentTab == Tab.RECIPE_SELECTION) {
            if (mouseX >= 20 && mouseX <= 270 && mouseY >= 80 && mouseY <= 85 + MAX_RECIPES_SHOWN * 20) {
                int recipeIndex = (int) ((mouseY - 80) / 20);
                int actualIndex = scrollOffset + recipeIndex;
                if (actualIndex >= 0 && actualIndex < filteredRecipes.size() && recipeIndex < MAX_RECIPES_SHOWN) {
                    String recipe = filteredRecipes.get(actualIndex);
                    SandboxWidget.AddResult result = widget.addToList(recipe, 1);
                    if (result == SandboxWidget.AddResult.FULL) {
                        Minecraft client = Minecraft.getInstance();
                        if (client.player != null) {
                            client.player.sendOverlayMessage(Component.literal("Shopping list full ("
                                + widget.getShoppingList().size() + "/" + widget.getMaxRecipes() + ")")
                                .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
                        }
                    } else {
                        rebuildWidgets();
                    }
                    return true;
                }
            }
            if (!tops().isEmpty() && mouseX >= treeViewX && mouseX <= treeViewX + treeViewWidth &&
                mouseY >= treeViewY && mouseY <= treeViewY + treeViewHeight) {
                return handleTreeNodeClick(mouseX, mouseY);
            }
        }
        return super.mouseClicked(ctx, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent ctx, double deltaX, double deltaY) {
        double mouseX = ctx.x();
        double mouseY = ctx.y();
        if (currentTab == Tab.POSITIONING) {
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
        }
        return super.mouseDragged(ctx, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent ctx) {
        if (currentTab == Tab.POSITIONING) {
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

    private boolean handleTreeNodeClick(double mouseX, double mouseY) {
        int x = treeViewX + 10;
        int y = treeViewY + 10 - treeScrollOffset;
        for (RecipeManager.RecipeNode top : tops()) {
            if (checkNodeClick(top, mouseX, mouseY, x, y, 0, SandboxWidget.LIST_KEY)) return true;
            y += getExpandedNodeHeight(top, SandboxWidget.LIST_KEY);
        }
        return false;
    }

    private boolean checkNodeClick(RecipeManager.RecipeNode node, double mouseX, double mouseY, int x, int y, int level, String pathKey) {
        if (node == null) return false;

        if (y + 16 < treeViewY || y > treeViewY + treeViewHeight) {
            y += 16;
            if (node.ingredients != null && !node.ingredients.isEmpty() &&
                widget.isNodeExpanded(SandboxWidget.makePathKey(pathKey, node.name))) {
                for (RecipeManager.RecipeNode child : node.ingredients) {
                    boolean childResult = checkNodeClick(child, mouseX, mouseY, x, y, level + 1, SandboxWidget.makePathKey(pathKey, node.name));
                    if (childResult) return true;
                    y += getExpandedNodeHeight(child, SandboxWidget.makePathKey(pathKey, node.name));
                }
            }
            return false;
        }

        int indent = level * RECIPE_LEVEL_INDENT;
        int nodeHeight = 16;
        boolean hasChildren = node.ingredients != null && !node.ingredients.isEmpty();
        int nodeWidth = treeViewWidth - 20 - indent;

    if (mouseY >= y && mouseY <= y + nodeHeight &&
            mouseY >= treeViewY && mouseY <= treeViewY + treeViewHeight) {
            if (mouseX >= x + indent && mouseX <= x + indent + nodeWidth) {
        String nodeKey = SandboxWidget.makePathKey(pathKey, node.name);
                if (hasChildren && mouseX <= x + indent + 25) {
                    widget.toggleNodeExpansion(nodeKey);
                    return true;
                }
                else if (hasChildren) {
                    widget.toggleNodeExpansion(nodeKey);
                    return true;
                }
                else {
                    Minecraft client = Minecraft.getInstance();
                    Map<String, Integer> resources = ResourcesManager.getInstance().getAllResources();
                    int available = resources.getOrDefault(node.name, 0);
                    boolean hasEnough = available >= node.required;
                    Component message = Component.literal("You have " + available + "/" + node.required + " of " + node.name)
                        .setStyle(Style.EMPTY.withColor(hasEnough ? ChatFormatting.GREEN : ChatFormatting.RED));
                    if (client.player != null) {
                        client.player.sendOverlayMessage(message);
                    }
                    return true;
                }
            }
        }

        y += nodeHeight;

    if (hasChildren && widget.isNodeExpanded(SandboxWidget.makePathKey(pathKey, node.name))) {
            for (RecipeManager.RecipeNode child : node.ingredients) {
        if (checkNodeClick(child, mouseX, mouseY, x, y, level + 1, SandboxWidget.makePathKey(pathKey, node.name))) {
                    return true;
                }
        y += getExpandedNodeHeight(child, SandboxWidget.makePathKey(pathKey, node.name));
            }
        }
        return false;
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

    private void applyConfiguration() {
        widget.setWidgetPosition(widgetPositionX, widgetPositionY);

        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            Component message = Component.literal("Widget configuration applied!")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN));
            client.player.sendOverlayMessage(message);
        }
    }

    private void saveWidgetConfiguration() {
        widget.saveConfiguration();
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            Component message = Component.literal("Widget configuration saved!")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN));
            client.player.sendOverlayMessage(message);
        }
    }

    @Override
    public void onClose() {
        if (widget.getWidgetX() != widgetPositionX || widget.getWidgetY() != widgetPositionY) {
            widget.setWidgetPosition(widgetPositionX, widgetPositionY);
        }
        widget.saveConfiguration();
        super.onClose();
    }


}
