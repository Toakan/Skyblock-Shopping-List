package inventoryreader.ir;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * Menu tab "Shopping List": search recipes and click to add them, edit amounts or remove entries, and see
 * the same trees the HUD shows.
 */
public class ShoppingListScreen extends Screen {
    private final SandboxWidget widget;
    private final RecipeManager recipeManager;
    private EditBox searchField;
    private List<String> filteredRecipes;
    private int scrollOffset = 0;
    /** Recipe rows that fit between the search box and the bottom buttons; set in init(). */
    private int MAX_RECIPES_SHOWN = 10;
    private static final int LIST_X = 20;
    /** Top of the first recipe row; rows are {@link #ROW_HEIGHT} tall. Drawing and clicks both use these. */
    private static final int LIST_TOP = 85;
    private static final int ROW_HEIGHT = 20;
    /** Left column (search + recipe list) right edge; set in init() from the screen width. */
    private int listRight = 270;
    private String searchText = "";
    /** How many of a recipe one click adds. */
    private int addAmount = 1;
    /** Recipe names the shopping-list rows were built for; rows are rebuilt when the list changes. */
    private List<String> builtRows = List.of();
    /** {@link #builtRows} as a set, for the per-frame "already on the list" check. */
    private java.util.Set<String> builtRowSet = java.util.Set.of();
    private static final int PANEL_TOP = 58;
    private static final int PANEL_ROW = 22;
    private static final int PANEL_MAX_ROWS = 5;
    private int treeViewX = 300;
    private int treeViewY = 80;
    private int treeViewWidth = 400;
    /** Room left for each shopping list row's name once its buttons are placed. */
    private int listNameWidth = 276;
    private int treeViewHeight = 300;
    private int treeScrollOffset = 0;
    private static final int RECIPE_LEVEL_INDENT = 10;
    private static final int GOLD = MenuTabs.GOLD;

    public ShoppingListScreen() {
        super(Component.literal(InventoryReader.NAME));
        this.widget = SandboxWidget.getInstance();
        this.recipeManager = RecipeManager.getInstance();
        this.filteredRecipes = new ArrayList<>(recipeManager.getRecipeNames());
    }

    @Override
    protected void init() {
        MenuTabs.buttons(MenuTabs.Tab.SHOPPING_LIST, width).forEach(this::addRenderableWidget);
        initRecipeTab();
        addRenderableWidget(Button.builder(hudLabel(), button -> {
            widget.setEnabled(!widget.isEnabled());
            button.setMessage(hudLabel());
        }).bounds(20, height - 30, 150, 20).build());
    }

    private Component hudLabel() {
        return Component.literal("HUD: " + (widget.isEnabled() ? "ON" : "OFF"));
    }

    private void initRecipeTab() {
        // Left column takes up to 250px, less on narrow screens; the right column gets the rest.
        int listWidth = Math.max(150, Math.min(250, (width - 60) / 2));
        listRight = LIST_X + listWidth;
        treeViewX = listRight + 20;
        MAX_RECIPES_SHOWN = Math.max(3, (height - 40 - LIST_TOP - 15) / ROW_HEIGHT);
        searchField = new EditBox(font, LIST_X, 55, listWidth - 54, 20, Component.literal(""));
        searchField.setMaxLength(50);
        searchField.setHint(Component.literal("Search recipes..."));
        searchField.setValue(searchText);
        searchField.setResponder(this::updateFilteredRecipes);
        addRenderableWidget(searchField);

        // How many to add when a recipe is clicked.
        EditBox amountBox = new EditBox(font, listRight - 40, 55, 40, 20, Component.literal("Amount"));
        amountBox.setMaxLength(6);
        amountBox.setValue(String.valueOf(addAmount));
        amountBox.setResponder(text -> {
            try {
                addAmount = Math.max(1, Integer.parseInt(text.trim()));
            } catch (NumberFormatException ignored) {
                addAmount = 1;
            }
        });
        addRenderableWidget(amountBox);

        // Shopping list panel (right column, above the preview): name, move up/down, amount box and remove
        // button per row. Order is priority: rows higher up get shared stock first.
        List<ShoppingListEntry> list = widget.getShoppingList();
        treeViewWidth = Math.max(120, Math.min(400, width - treeViewX - 20));
        // Narrow screens: shrink the move buttons, then drop them, so the name keeps some room.
        int moveWidth = treeViewWidth >= 200 ? 20 : treeViewWidth >= 164 ? 12 : 0;
        // Row, right to left: × (-26), amount (-70), Have total / Add more (-88), ▼, ▲.
        int modeX = -88;
        int controlsLeft = moveWidth > 0 ? -modeX + 4 + 2 * moveWidth : -modeX;
        listNameWidth = treeViewWidth - controlsLeft - 8;
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
            boolean total = entry.isHaveTotal();
            addRenderableWidget(Button.builder(Component.literal(total ? "=" : "+"), button -> {
                widget.setEntryHaveTotal(entry.recipe, !total);
                rebuildWidgets();
            }).bounds(treeViewX + treeViewWidth + modeX, y, 16, 18)
              .tooltip(Tooltip.create(Component.literal(total
                  ? "Have total (=): the amount is how many you want in total; what you hold counts.\nClick for Add more."
                  : "Add more (+): the amount is how many more to make on top of what you hold.\nClick for Have total.")))
              .build());
            if (moveWidth > 0) {
                Button up = Button.builder(Component.literal("▲"), button -> {
                    widget.moveEntry(entry.recipe, -1);
                    rebuildWidgets();
                }).bounds(treeViewX + treeViewWidth - controlsLeft, y, moveWidth, 18)
                  .tooltip(Tooltip.create(Component.literal("Move up: higher rows get shared materials first.")))
                  .build();
                up.active = i > 0;
                addRenderableWidget(up);
                Button down = Button.builder(Component.literal("▼"), button -> {
                    widget.moveEntry(entry.recipe, 1);
                    rebuildWidgets();
                }).bounds(treeViewX + treeViewWidth + modeX - 2 - moveWidth, y, moveWidth, 18)
                  .tooltip(Tooltip.create(Component.literal("Move down: lower rows get what is left.")))
                  .build();
                down.active = i < list.size() - 1;
                addRenderableWidget(down);
            }
            addRenderableWidget(Button.builder(Component.literal("×"), button -> {
                widget.removeFromList(entry.recipe);
                rebuildWidgets();
            }).bounds(treeViewX + treeViewWidth - 26, y, 20, 18).build());
        }
        builtRows = list.stream().map(e -> e.recipe).toList();
        builtRowSet = java.util.Set.copyOf(builtRows);
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
        List<String> names = widget.getShoppingList().stream().map(e -> e.recipe).toList();
        if (!names.equals(builtRows)) rebuildWidgets();
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

    private void updateFilteredRecipes(String searchTerm) {
        searchText = searchTerm == null ? "" : searchTerm;
        if (searchTerm == null || searchTerm.isEmpty()) {
            this.filteredRecipes = new ArrayList<>(recipeManager.getRecipeNames());
        } else {
            String lowerSearchTerm = searchTerm.toLowerCase(Locale.ROOT);
            this.filteredRecipes = recipeManager.getRecipeNames().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).contains(lowerSearchTerm))
                .collect(Collectors.toList());
        }
        scrollOffset = 0;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        renderRecipeTab(context, mouseX, mouseY);
        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    private void renderRecipeTab(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        context.fill(0, 0, width, height, 0xFF0E0E0E);
        context.outline(0, 0, width, height, 0x88608C35);

        MenuTabs.renderHeader(context, font, width, MenuTabs.Tab.SHOPPING_LIST);

        context.text(font,
            Component.literal("Click a recipe to add it").setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true)),
            20, 47, GOLD, false);
        context.text(font, "×", listRight - 50, 61, 0xFFCCCCCC, false);
        renderListPanel(context);
        context.text(font,
            Component.literal("Preview").setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true)),
            treeViewX, treeViewY - 13, GOLD, false);
        int yPos = LIST_TOP;
        int itemHeight = ROW_HEIGHT;
        int endIndex = Math.min(scrollOffset + MAX_RECIPES_SHOWN, filteredRecipes.size());
        java.util.Set<String> inList = builtRowSet;

        context.fill(LIST_X, yPos - 5, listRight, yPos + MAX_RECIPES_SHOWN * itemHeight + 15, 0xFC271910);

        int listBorderColor = 0xFFDAA520;
        for (int i = 0; i < 2; i++) {
            context.outline(20 - i,
                yPos - 5 - i,
                listRight - LIST_X + i * 2,
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
                    context.fill(LIST_X, yPos, listRight, yPos + itemHeight, 0x99608C35);
                    context.text(font, fitPlain(recipe, listRight - LIST_X - 20, 0xFFFFB728), LIST_X + 10, yPos + 5, 0xFFFFFFFF, false);
                } else {
                    boolean isHovered = mouseX >= LIST_X && mouseX <= listRight && mouseY >= yPos && mouseY <= yPos + itemHeight;
                    if (isHovered) {
                        context.fill(LIST_X, yPos, listRight, yPos + itemHeight, 0x553E6428);
                    }
                    context.text(font, fitPlain(recipe, listRight - LIST_X - 20, 0xFFE0E0E0), LIST_X + 10, yPos + 5, 0xFFFFFFFF, false);
                }
                yPos += itemHeight;
            }
            if (scrollOffset > 0) {
                String up = "▲";
                context.text(font, up, (LIST_X + listRight) / 2 - font.width(up) / 2, 75, GOLD, false);
            }
            if (endIndex < filteredRecipes.size()) {
                String down = "▼";
                context.text(font, down, (LIST_X + listRight) / 2 - font.width(down) / 2, yPos + 5, GOLD, false);
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
            int treeTop = treeViewY + 10 - treeScrollOffset;
            int treeY = treeTop;
            for (RecipeManager.RecipeNode top : tops()) {
                treeY = renderRecipeTree(context, top, treeViewX + 10, treeY, 0, SandboxWidget.LIST_KEY, mouseX, mouseY);
            }
            // The draw pass already walked the tree; its end is the height.
            int totalHeight = treeY - treeTop;
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
            context.text(font, "Click recipes on the left", treeViewX + 10, treeViewY + 20, 0xFFAAAAAA, false);
        }
        context.disableScissor();
    }

    /** {@code text} in the default font, cut with "..." to fit {@code width}. */
    private Component fitPlain(String text, int width, int color) {
        Style style = Style.EMPTY.withColor(color & 0xFFFFFF);
        return SandboxWidget.fitName(font, t -> Component.literal(t).setStyle(style), text, width);
    }

    private void renderListPanel(GuiGraphicsExtractor context) {
        // Names as of the last rebuild (tick() rebuilds when the list changes): no copy of the list per frame.
        List<String> list = builtRows;
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
            context.text(font, fitPlain(list.get(i), listNameWidth, 0xFFE0E0E0), treeViewX + 4, y + 5, 0xFFFFFFFF, false);
        }
        if (list.size() > rows) {
            context.text(font, "+" + (list.size() - rows) + " more", treeViewX + 4, PANEL_TOP + rows * PANEL_ROW, 0xFFAAAAAA, false);
        }
    }

    /**
     * One row of the preview tree, drawn with the HUD's look (Settings > Appearance: colours, font, row boxes,
     * tree lines, bold names, forge times). Rows keep this screen's fixed 16 px height so clicks line up.
     */
    private int renderRecipeTree(GuiGraphicsExtractor context, RecipeManager.RecipeNode node, int x, int y, int level,
                                 String pathKey, int mouseX, int mouseY) {
        if (node == null) return y;
        HudStyle style = HudStyle.get();
        int indent = level * RECIPE_LEVEL_INDENT;
        boolean hasEnough = node.amount <= 0 && node.toCraft <= 0 && !node.cooking;
        boolean showRemaining = widget.isShowRemaining();
        String nodeKey = SandboxWidget.makePathKey(pathKey, node.name);
        boolean isExpanded = widget.isNodeExpanded(nodeKey);
        boolean hasChildren = node.ingredients != null && !node.ingredients.isEmpty();
        int nodeWidth = treeViewWidth - 20 - indent;
        int rowX = x + indent;

        if (style.showRowBoxes) {
            RoundedBox.fill(context, rowX, y, nodeWidth, 16, style.rowRadius, style.rowBackground);
            int border = SandboxWidget.progressBorderColor(node, showRemaining);
            for (int i = 0; i < style.rowBorderWidth; i++) {
                RoundedBox.outline(context, rowX + i, y + i, nodeWidth - 2 * i, 16 - 2 * i, Math.max(0, style.rowRadius - i), border);
            }
        }
        if (mouseX >= rowX && mouseX <= rowX + nodeWidth && mouseY >= y && mouseY <= y + 16) {
            context.fill(rowX, y, rowX + nodeWidth, y + 16, 0x22FFFFFF);
        }
        if (hasChildren) {
            context.text(font, style.text(isExpanded ? "▼" : "▶", style.itemText, false), rowX + 5, y + 4, 0xFFFFFFFF, style.textShadow);
        }

        int statusColor = SandboxWidget.progressColor(node, showRemaining);
        int nameColor = level == 0 ? style.rootText : hasEnough ? style.itemText : statusColor;
        boolean bold = level == 0 && style.boldRootNames;
        Component amount = style.text(SandboxWidget.amountText(node) + " ", statusColor, false);
        int amountX = rowX + (hasChildren ? 25 : 10);
        context.text(font, amount, amountX, y + 4, 0xFFFFFFFF, style.textShadow);

        // Long names are cut with "..." so the forge time (if any) always fits.
        String forge = SandboxWidget.forgeText(node, level);
        Component tag = forge.isEmpty() ? Component.empty() : style.text(forge, nameColor, false);
        int nameX = amountX + font.width(amount);
        int room = rowX + nodeWidth - 4 - nameX - font.width(tag);
        Component name = SandboxWidget.fitName(font, style, node.name, nameColor, bold, room);
        context.text(font, name, nameX, y + 4, 0xFFFFFFFF, style.textShadow);
        context.text(font, tag, nameX + font.width(name), y + 4, 0xFFFFFFFF, style.textShadow);

        y += 16;

        if (hasChildren && isExpanded) {
            for (RecipeManager.RecipeNode child : node.ingredients) {
                if (style.showTreeLines) {
                    int lineStartX = rowX + 6;
                    int childIndentX = x + indent + RECIPE_LEVEL_INDENT;
                    context.fill(lineStartX, y, lineStartX + 1, y + 8, style.treeLines);
                    context.fill(lineStartX, y + 8, childIndentX, y + 9, style.treeLines);
                }
                y = renderRecipeTree(context, child, x, y, level + 1, nodeKey, mouseX, mouseY);
            }
        }
        return y;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= LIST_X && mouseX <= listRight && mouseY >= LIST_TOP - 5) {
            // Wheel down (negative) moves the list down, matching the tree view and vanilla lists.
            if (verticalAmount > 0 && scrollOffset > 0) {
                scrollOffset--;
                return true;
            } else if (verticalAmount < 0 && scrollOffset + MAX_RECIPES_SHOWN < filteredRecipes.size()) {
                scrollOffset++;
                return true;
            }
        }
        if (mouseX >= treeViewX && mouseX <= treeViewX + treeViewWidth &&
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
        if (mouseX >= LIST_X && mouseX <= listRight && mouseY >= LIST_TOP && mouseY < LIST_TOP + MAX_RECIPES_SHOWN * ROW_HEIGHT) {
            int recipeIndex = (int) ((mouseY - LIST_TOP) / ROW_HEIGHT);
            int actualIndex = scrollOffset + recipeIndex;
            if (actualIndex >= 0 && actualIndex < filteredRecipes.size() && recipeIndex < MAX_RECIPES_SHOWN) {
                String recipe = filteredRecipes.get(actualIndex);
                SandboxWidget.AddResult result = widget.addToList(recipe, addAmount);
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
        return super.mouseClicked(ctx, doubleClick);
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
                if (hasChildren) {
                    widget.toggleNodeExpansion(nodeKey);
                    return true;
                }
                else {
                    Minecraft client = Minecraft.getInstance();
                    int available = ResourcesManager.getInstance().getResourceByName(node.name);
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

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
