package inventoryreader.ir;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SandboxWidget {
    private static final Identifier SANDBOX_WIDGET_LAYER = Identifier.fromNamespaceAndPath(InventoryReader.MOD_ID, "sandbox_widget");
    private static final int DARK_PANEL_BG = 0x99271910;
    private static final int HEADER_BG = 0xCC2C4A1B;
    private static final int GOLD = 0xFFFFB728;
    private static final int RECIPE_LEVEL_INDENT = 10;
    private static final SandboxWidget INSTANCE = new SandboxWidget();
    /** Expansion-key root for the top-level nodes (Total and each recipe). */
    public static final String LIST_KEY = "list";
    public static final int MAX_RECIPES_LIMIT = 10;
    private static final SystemToast.SystemToastId READY_TOAST = new SystemToast.SystemToastId(5000L);
    private static final SystemToast.SystemToastId ACHIEVED_TOAST = new SystemToast.SystemToastId(5000L);
    private volatile boolean enabled = false;
    /** Entries are never mutated in place; changes replace the entry so the update thread sees whole values. */
    private final List<ShoppingListEntry> shoppingList = new CopyOnWriteArrayList<>();
    /**
     * Written by the update thread, read by the render thread; always replaced, never mutated. A synthetic
     * root whose children (Total, then one tree per recipe) are what the HUD shows.
     */
    private volatile RecipeManager.RecipeNode recipeTree = null;
    private int widgetX = 10;
    private int widgetY = 40;
    private int widgetWidth = 250;
    private int widgetHeight = 300;
    private Map<String, Boolean> expandedNodes = new ConcurrentHashMap<>();
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private volatile boolean showRemaining = true;
    private volatile boolean showTotal = true;
    private volatile boolean notifications = true;
    private volatile boolean autoRemove = true;
    private volatile int maxRecipes = 3;
    /** Update thread only: entries already announced as ready / achieved, so each toast fires once. */
    private final Set<String> readyEntries = new HashSet<>();
    private final Set<String> achievedEntries = new HashSet<>();
    /** Update thread only: the first computation after start-up sets the baseline without toasting. */
    private boolean baselineSet = false;
    private final ResourcesManager resourcesManager;
    private final ScheduledExecutorService scheduler;
    /** Resource version the current tree was computed from; -1 forces a recompute. */
    private volatile long computedVersion = -1;
    private int currentNodeLineHeight = 16;
    private float currentTreeScale = 1.0f;

    private SandboxWidget() {
        this.resourcesManager = ResourcesManager.getInstance();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "IR-WidgetUpdate");
            t.setDaemon(true);
            return t;
        });
        HudElementRegistry.addLast(SANDBOX_WIDGET_LAYER, (context, tickCounter) -> {
            if (enabled && !shoppingList.isEmpty() && recipeTree != null && SkyblockDetector.isOnSkyblock()) {
                render(context);
            }
        });
        loadConfiguration();
        scheduler.scheduleWithFixedDelay(this::refreshIfStale, 0, 1, TimeUnit.SECONDS);
    }

    public static SandboxWidget getInstance() {
        return INSTANCE;
    }
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        requestRefresh();
        saveConfiguration();
    }
    public boolean isEnabled() {
        return this.enabled;
    }
    public enum AddResult { ADDED, INCREASED, FULL }

    /** Adds a recipe, or raises its amount if it is already on the list. Refused when the list is full. */
    public synchronized AddResult addToList(String recipe, int amount) {
        amount = Math.max(1, amount);
        for (int i = 0; i < shoppingList.size(); i++) {
            ShoppingListEntry e = shoppingList.get(i);
            if (e.recipe.equals(recipe)) {
                shoppingList.set(i, new ShoppingListEntry(recipe, e.amount + amount, e.startCount));
                listChanged();
                return AddResult.INCREASED;
            }
        }
        if (shoppingList.size() >= maxRecipes) return AddResult.FULL;
        shoppingList.add(new ShoppingListEntry(recipe, amount, resourcesManager.getResourceByName(recipe)));
        expandedNodes.putIfAbsent(makePathKey(LIST_KEY, recipe), true);
        listChanged();
        return AddResult.ADDED;
    }
    public synchronized void setEntryAmount(String recipe, int amount) {
        for (int i = 0; i < shoppingList.size(); i++) {
            ShoppingListEntry e = shoppingList.get(i);
            if (e.recipe.equals(recipe) && e.amount != Math.max(1, amount)) {
                shoppingList.set(i, new ShoppingListEntry(recipe, Math.max(1, amount), e.startCount));
                listChanged();
                return;
            }
        }
    }
    public synchronized void removeFromList(String recipe) {
        if (shoppingList.removeIf(e -> e.recipe.equals(recipe))) listChanged();
    }
    public synchronized void clearList() {
        if (shoppingList.isEmpty()) return;
        shoppingList.clear();
        listChanged();
    }
    public List<ShoppingListEntry> getShoppingList() {
        List<ShoppingListEntry> copy = new ArrayList<>();
        for (ShoppingListEntry e : shoppingList) copy.add(e.copy());
        return copy;
    }
    private void listChanged() {
        requestRefresh();
        saveConfiguration();
    }
    /** HUD heading: the recipe when there is one, otherwise the list size. */
    public String getTitle() {
        List<ShoppingListEntry> list = shoppingList;
        if (list.isEmpty()) return "Shopping list (empty)";
        if (list.size() == 1) return "Recipe: " + list.get(0).recipe;
        return "Shopping list (" + list.size() + ")";
    }
    /** The synthetic root the HUD draws (children: Total, then one tree per recipe), or null. */
    public RecipeManager.RecipeNode getDisplayRoot() {
        return recipeTree;
    }
    public int getWidgetX() {
        return widgetX;
    }
    public int getWidgetY() {
        return widgetY;
    }
    public void setWidgetPosition(int x, int y) {
        this.widgetX = x;
        this.widgetY = y;
        saveConfiguration();
    }
    public int getWidgetWidth() { return widgetWidth; }
    public int getWidgetHeight() { return widgetHeight; }
    public void setWidgetSize(int width, int height) {
        Minecraft client = Minecraft.getInstance();
        int screenW = client.getWindow().getGuiScaledWidth();
        int screenH = client.getWindow().getGuiScaledHeight();
        this.widgetWidth = Math.max(180, Math.min(width, screenW - 20));
        this.widgetHeight = Math.max(120, Math.min(height, screenH - 20));
        saveConfiguration();
    }
    public boolean isNodeExpanded(String nodeKey) {
        Boolean result = expandedNodes.getOrDefault(nodeKey, false);
        return result != null ? result : false;
    }
    public void setNodeExpansion(String nodeKey, boolean expanded) {
        expandedNodes.put(nodeKey, expanded);
    }
    public void toggleNodeExpansion(String nodeKey) {
        Boolean currentState = expandedNodes.getOrDefault(nodeKey, false);
        if (currentState == null) {
            currentState = false;
        }

        boolean newState = !currentState;
        expandedNodes.put(nodeKey, newState);
        saveConfiguration();
    }
    public void saveConfiguration() {
        WidgetConfig config = new WidgetConfig();
        config.enabled = enabled;
        config.widgetX = widgetX;
        config.widgetY = widgetY;
        config.widgetWidth = widgetWidth;
        config.widgetHeight = widgetHeight;
        config.expandedNodes = new HashMap<>(expandedNodes);
        config.shoppingList = getShoppingList();
        config.showRemaining = showRemaining;
        config.showTotal = showTotal;
        config.notifications = notifications;
        config.autoRemove = autoRemove;
        config.maxRecipes = maxRecipes;
        JsonFiles.write(FilePathManager.WIDGET_CONFIG_JSON, config);
    }
    private void loadConfiguration() {
        WidgetConfig config = JsonFiles.read(FilePathManager.WIDGET_CONFIG_JSON, WidgetConfig.class);
        if (config == null) return;
        this.enabled = config.enabled;
        this.widgetX = config.widgetX;
        this.widgetY = config.widgetY;
        if (config.expandedNodes != null) {
            this.expandedNodes = new ConcurrentHashMap<>(config.expandedNodes);
        }
        if (config.showRemaining != null) this.showRemaining = config.showRemaining;
        if (config.showTotal != null) this.showTotal = config.showTotal;
        if (config.notifications != null) this.notifications = config.notifications;
        if (config.autoRemove != null) this.autoRemove = config.autoRemove;
        if (config.maxRecipes != null) this.maxRecipes = clampMaxRecipes(config.maxRecipes);
        if (config.widgetWidth > 0) this.widgetWidth = config.widgetWidth;
        if (config.widgetHeight > 0) this.widgetHeight = config.widgetHeight;
        shoppingList.clear();
        if (config.shoppingList != null) {
            for (ShoppingListEntry e : config.shoppingList) {
                if (e != null && e.recipe != null && !e.recipe.isBlank()) {
                    shoppingList.add(new ShoppingListEntry(e.recipe, Math.max(1, e.amount), e.startCount));
                }
            }
        } else if (config.selectedRecipe != null) {
            // Config from before the shopping list: keep the one recipe it tracked.
            int amount = config.craftAmount != null && config.craftAmount > 0 ? config.craftAmount : 1;
            shoppingList.add(new ShoppingListEntry(config.selectedRecipe, amount, resourcesManager.getResourceByName(config.selectedRecipe)));
        }
    }
    /** Restores defaults after a reset deleted the config file. */
    public void resetConfiguration() {
        enabled = false;
        shoppingList.clear();
        recipeTree = null;
        widgetX = 10;
        widgetY = 40;
        widgetWidth = 250;
        widgetHeight = 300;
        expandedNodes = new ConcurrentHashMap<>();
        showRemaining = true;
        showTotal = true;
        notifications = true;
        autoRemove = true;
        maxRecipes = 3;
        messages.clear();
        saveConfiguration();
        requestRefresh();
    }
    private static class WidgetConfig {
        boolean enabled;
        int widgetX;
        int widgetY;
        int widgetWidth;
        int widgetHeight;
        Map<String, Boolean> expandedNodes;
        List<ShoppingListEntry> shoppingList;
        /** Read only, for configs written before the shopping list existed. */
        String selectedRecipe;
        /** Read only, for configs written before the shopping list existed. */
        Integer craftAmount;
        // Boxed so configs written before an option existed get its default.
        Boolean showRemaining;
        Boolean showTotal;
        Boolean notifications;
        Boolean autoRemove;
        Integer maxRecipes;
    }
    /** Marks the tree stale; the update thread recomputes it within a second. */
    private void requestRefresh() {
        computedVersion = -1;
        scheduler.execute(this::refreshIfStale);
    }

    /** Runs on the update thread. Recomputes only when resources or the selection changed. */
    private void refreshIfStale() {
        try {
            long version = resourcesManager.getVersion();
            if (version == computedVersion) return;
            computedVersion = version;
            updateRecipeData();
        } catch (RuntimeException e) {
            // Never let an exception escape: it would cancel the scheduled refresh for good.
            InventoryReader.LOGGER.error("Failed to update HUD recipe", e);
        }
    }

    private void updateRecipeData() {
        List<ShoppingListEntry> entries = getShoppingList();
        if (entries.isEmpty()) {
            recipeTree = null;
            messages.clear();
            readyEntries.clear();
            achievedEntries.clear();
            baselineSet = true;
            return;
        }

        ResourcesManager.ShoppingResponse response = resourcesManager.getShoppingList(entries);
        boolean announce = baselineSet;
        baselineSet = true;

        List<String> toRemove = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            ShoppingListEntry entry = entries.get(i);
            RecipeManager.RecipeNode tree = response.trees.get(i);
            names.add(entry.recipe);
            boolean achieved = resourcesManager.getResourceByName(entry.recipe) >= entry.startCount + entry.amount;
            if (achieved) {
                if (achievedEntries.add(entry.recipe) && announce) {
                    notifyPlayer(ACHIEVED_TOAST, "Item achieved", entry.amount + "× " + entry.recipe);
                }
                if (autoRemove) toRemove.add(entry.recipe);
                continue;
            }
            achievedEntries.remove(entry.recipe);
            boolean ready = tree.ingredients != null && !tree.ingredients.isEmpty()
                && tree.ingredients.stream().allMatch(child -> child.amount <= 0);
            if (!ready) {
                readyEntries.remove(entry.recipe);
            } else if (readyEntries.add(entry.recipe) && announce) {
                notifyPlayer(READY_TOAST, "Ready to craft", entry.amount + "× " + entry.recipe);
            }
        }
        readyEntries.retainAll(names);
        achievedEntries.retainAll(names);
        if (!toRemove.isEmpty()) {
            // Removing schedules another refresh, which redraws without these entries.
            for (String recipe : toRemove) removeFromList(recipe);
            return;
        }

        List<String> newMessages = new ArrayList<>();
        newMessages.add("Craftable -");
        List<Map.Entry<String, Integer>> sortedEntries = new ArrayList<>(response.craftable.entrySet());
        sortedEntries.sort((e1, e2) -> e2.getValue().compareTo(e1.getValue()));
        for (Map.Entry<String, Integer> entry : sortedEntries) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                newMessages.add("   " + entry.getValue() + "× " + entry.getKey());
            }
        }

        List<RecipeManager.RecipeNode> tops = new ArrayList<>();
        if (showTotal && !response.total.ingredients.isEmpty()) {
            tops.add(response.total);
            expandedNodes.putIfAbsent(makePathKey(LIST_KEY, response.total.name), true);
        }
        for (RecipeManager.RecipeNode tree : response.trees) {
            tops.add(tree);
            expandedNodes.putIfAbsent(makePathKey(LIST_KEY, tree.name), true);
        }
        messages.clear();
        messages.addAll(newMessages);
        recipeTree = new RecipeManager.RecipeNode("Shopping list", 0, 0, tops);
    }

    /** Client-side toast; never sent anywhere. Off when notifications are disabled or outside SkyBlock. */
    private void notifyPlayer(SystemToast.SystemToastId id, String title, String message) {
        if (!notifications || !SkyblockDetector.isOnSkyblock()) return;
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> SystemToast.add(client.gui.toastManager(), id,
            Component.literal(title).withStyle(ChatFormatting.GOLD), Component.literal(message)));
    }

    private void render(GuiGraphicsExtractor context) {
        RecipeManager.RecipeNode root = this.recipeTree;
        if (!enabled || root == null) {
            return;
        }
    Minecraft client = Minecraft.getInstance();
    int height = client.getWindow().getGuiScaledHeight();
    int panelWidth = widgetWidth;
    int visibleLines = 0;
    for (RecipeManager.RecipeNode top : root.ingredients) visibleLines += countVisibleRecipeTreeLines(top, LIST_KEY);
    int panelMaxHeight = Math.min(height - 40, widgetHeight);

    int recipeTreeHeightMax = Math.max(0, visibleLines * 16);
    int messageLinesRaw = countMessageLines(client, panelWidth);
    int availableForMessagesMax = Math.max(0, Math.min((int)(height * 0.4), panelMaxHeight - 20 - recipeTreeHeightMax - 15));
    int messageSectionHeightEst = messageLinesRaw > 0 ? Math.min(messageLinesRaw * 10 + 20, availableForMessagesMax) : 0;

    int availableTreeHeight1 = Math.max(0, panelMaxHeight - 22 - (messageSectionHeightEst > 0 ? (messageSectionHeightEst + 15) : 0));
    int safeLines = Math.max(1, visibleLines);
    int computedLine1 = safeLines > 0 ? Math.max(6, (int)Math.floor((float)availableTreeHeight1 / (float)safeLines)) : 16;
    computedLine1 = Math.min(16, computedLine1);
    int treeHeightActual = safeLines * computedLine1;

    int availableForMessages2 = Math.max(0, Math.min((int)(height * 0.4), panelMaxHeight - 20 - treeHeightActual - 15));
    int messageSectionHeight = messageLinesRaw > 0 ? Math.min(messageLinesRaw * 10 + 20, availableForMessages2) : 0;
    int desiredPanelHeight = 20 + treeHeightActual + messageSectionHeight + 15;
    int panelHeight = Math.min(panelMaxHeight, desiredPanelHeight);

        int panelX = widgetX;
        int panelY = widgetY;

    context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, DARK_PANEL_BG);

        context.fill(panelX, panelY, panelX + panelWidth, panelY + 20, HEADER_BG);

        int borderColor = 0xFFDAA520;
        int borderThickness = 2;
        for (int i = 0; i < borderThickness; i++) {
            context.outline(panelX - i,
                panelY - i,
                panelWidth + i * 2,
                panelHeight + i * 2,
                borderColor
            );
        }

        Component title = Component.literal(getTitle())
            .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true));
        int titleWidth = client.font.width(title);
        int maxTitleWidth = Math.max(20, panelWidth - 10);
        float titleScale = titleWidth > maxTitleWidth ? (float)maxTitleWidth / (float)titleWidth : 1.0f;
        context.pose().pushMatrix();
        context.pose().translate(panelX + (panelWidth - Math.min(titleWidth, maxTitleWidth)) / 2f, panelY + 5);
        context.pose().scale(titleScale, titleScale);
        context.text(
            client.font,
            title,
            0,
            0,
            0xFFFFFFFF,
            false
        );
        context.pose().popMatrix();


        context.fill(panelX, panelY + 19, panelX + panelWidth, panelY + 20, 0x99608C35);

    int y = panelY + 22;
    int availableTreeHeight = Math.max(0, panelHeight - 22 - (messageSectionHeight > 0 ? (messageSectionHeight + 15) : 0));
    int computedLine = safeLines > 0 ? Math.max(6, (int)Math.floor((float)availableTreeHeight / (float)safeLines)) : 16;
    computedLine = Math.min(16, computedLine);
    currentNodeLineHeight = computedLine;
    currentTreeScale = currentNodeLineHeight / 16.0f;
    int treeEndY = y;
    for (RecipeManager.RecipeNode top : root.ingredients) treeEndY = renderRecipeTree(context, top, panelX, treeEndY, 0, LIST_KEY);

        if (messageSectionHeight > 0) {
            context.fill(panelX, treeEndY, panelX + panelWidth, treeEndY + 1, 0x99608C35);

            int messageY = treeEndY + 6;

            int maxMessageY = panelY + panelHeight - 5;

            drawMessages(context, panelX, messageY, panelWidth, maxMessageY);
        }
    }
    private int countVisibleRecipeTreeLines(RecipeManager.RecipeNode node, String pathKey) {
        if (node == null) return 0;
        int count = 1;

        // Use path-based key to keep expansion stable regardless of amounts
        String nodeKey = makePathKey(pathKey, node.name);
        boolean isExpanded = expandedNodes.getOrDefault(nodeKey, false);

        if (node.ingredients != null && !node.ingredients.isEmpty() && isExpanded) {
            for (RecipeManager.RecipeNode child : node.ingredients) {
                count += countVisibleRecipeTreeLines(child, makePathKey(pathKey, node.name));
            }
        }
        return count;
    }
    private int renderRecipeTree(GuiGraphicsExtractor context, RecipeManager.RecipeNode node, int x, int y, int level, String pathKey) {
        if (node == null) return y;
        Minecraft client = Minecraft.getInstance();
        int unitIndent = Math.max(4, Math.round(RECIPE_LEVEL_INDENT * currentTreeScale));
        int indent = level * unitIndent;
        boolean hasEnough = (node.amount <= 0);
        String nodeKey = makePathKey(pathKey, node.name);
        boolean isExpanded = expandedNodes.getOrDefault(nodeKey, false);
        boolean hasChildren = node.ingredients != null && !node.ingredients.isEmpty();
        int bgColor = 0x99271910;
        int mouseX = (int)(client.mouseHandler.xpos() / client.getWindow().getGuiScale());
        int mouseY = (int)(client.mouseHandler.ypos() / client.getWindow().getGuiScale());
        int nodeBaseWidth = Math.max(100, widgetWidth - 20); // account for panel padding
        int nodeHeight = Math.max(6, currentNodeLineHeight);
        boolean isHovered = mouseX >= x + indent && mouseX <= x + indent + nodeBaseWidth - indent &&
                            mouseY >= y && mouseY <= y + nodeHeight;
        int nodeWidth = nodeBaseWidth - indent;
        context.fill(x + indent, y, x + indent + nodeWidth, y + nodeHeight, bgColor);
        if (isHovered) {
            context.fill(x + indent, y, x + indent + nodeWidth, y + nodeHeight, 0x22FFFFFF);
        }
        context.outline(x + indent, y, nodeWidth, nodeHeight, progressBorderColor(node, showRemaining));
        if (hasChildren) {
            String expandIcon = isExpanded ? "▼" : "▶";
            context.text(
                client.font,
                expandIcon,
                x + indent + Math.max(3, Math.round(5 * currentTreeScale)),
                y + Math.max(1, Math.round(4 * currentTreeScale)),
                0xFFFFFFFF,
                false
            );
        }
    int nameX = x + indent + (hasChildren ? Math.max(14, Math.round(25 * currentTreeScale)) : Math.max(6, Math.round(10 * currentTreeScale)));
        int textColor;
        boolean isBold = (level == 0);
        if (level == 0) {
            textColor = GOLD;
        } else {
            textColor = hasEnough ? 0xFFFFFFFF : progressColor(node, showRemaining);
        }
        String amountText = displayedAmount(node, showRemaining) + "× ";
        int amountColor = progressColor(node, showRemaining);
        Component itemName = Component.literal(node.name)
            .setStyle(Style.EMPTY.withColor(textColor).withBold(isBold));
        int amountWidth = client.font.width(amountText);
        int itemWidth = client.font.width(itemName);
        int totalTextWidth = amountWidth + itemWidth;
        int maxTextWidth = Math.max(10, nodeWidth - (hasChildren ? Math.max(14, Math.round(25 * currentTreeScale)) : Math.max(6, Math.round(10 * currentTreeScale))));
        int adjustedMaxTextWidth = (int)Math.floor(maxTextWidth / Math.max(0.01f, currentTreeScale));
        float textScaleLocal = totalTextWidth > adjustedMaxTextWidth ? (float)adjustedMaxTextWidth / (float)totalTextWidth : 1.0f;
        float textScale = Math.min(1.0f, textScaleLocal) * currentTreeScale;
        context.pose().pushMatrix();
        context.pose().translate(nameX, y + Math.max(1, Math.round(4 * currentTreeScale)));
        context.pose().scale(textScale, textScale);
        context.text(
            client.font,
            amountText,
            0,
            0,
            amountColor,
            false
        );
        context.text(
            client.font,
            itemName,
            amountWidth,
            0,
            0xFFFFFFFF,
            false
        );
        context.pose().popMatrix();
        y += nodeHeight;
    if (hasChildren && isExpanded && node.ingredients.size() > 0) {
            int lineColor = 0xFF777777;
            for (int i = 0; i < node.ingredients.size(); i++) {
                RecipeManager.RecipeNode child = node.ingredients.get(i);
                int lineStartX = x + indent + Math.max(4, Math.round(6 * currentTreeScale));
                int vertLineY = y;
                int childIndentX = x + indent + unitIndent;
                int vertLen = Math.max(3, Math.round(8 * currentTreeScale));
                context.fill(lineStartX, vertLineY, lineStartX + 1, vertLineY + vertLen, lineColor);
                context.fill(lineStartX, vertLineY + vertLen, childIndentX, vertLineY + vertLen + 1, lineColor);
        int nextY = renderRecipeTree(context, child, x, y, level + 1, nodeKey);
                y = nextY;
            }
        }
        return y;
    }
    private void drawMessages(GuiGraphicsExtractor context, int x, int y, int width, int maxY) {
        Minecraft client = Minecraft.getInstance();
        int baseHeader = 13;
        int baseLine = 10;
        int availableHeight = Math.max(0, maxY - y);
        int lines = countMessageLines(client, width);
        int desiredHeight = baseHeader + Math.max(0, (lines - 1) * baseLine);
        float scale = desiredHeight > 0 ? Math.min(1.0f, Math.max(0.4f, (float)availableHeight / (float)desiredHeight)) : 1.0f;

        if (y + Math.round(baseLine * scale) <= maxY) {
            Component messagesHeader = Component.literal("Craftable -")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW).withBold(true));
            context.pose().pushMatrix();
            context.pose().translate(x + 5, y);
            context.pose().scale(scale, scale);
            context.text(
                client.font,
                messagesHeader,
                0,
                0,
                0xFFFFFFFF,
                false
            );
            context.pose().popMatrix();
        } else {
            return;
        }

        if (messages.size() == 1 && messages.get(0).equals("Craftable -")) {
            return;
        }
        y += Math.round(baseHeader * scale);

        List<String> messagesCopy = new ArrayList<>(messages);
        messagesCopy.sort((a, b) -> {
            if (a.equals("Craftable -")) return -1;
            if (b.equals("Craftable -")) return 1;
            try {
                int amountA = extractAmount(a);
                int amountB = extractAmount(b);
                return Integer.compare(amountB, amountA);
            } catch (Exception e) {
                return 0;
            }
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
                    context.pose().translate(x + 5, y);
                    context.pose().scale(scale, scale);
                    context.text(
                        client.font,
                        line.toString(),
                        0,
                        0,
                        textColor,
                        false
                    );
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
                context.pose().translate(x + 5, y);
                context.pose().scale(scale, scale);
                context.text(
                    client.font,
                    line.toString(),
                    0,
                    0,
                    textColor,
                    false
                );
                context.pose().popMatrix();
                y += Math.round((baseLine - 1) * scale);
            }
        }
    }

    private int extractAmount(String message) {
        try {
            int xIndex = message.indexOf('×');
            if (xIndex > 0) {
                String amountStr = message.substring(0, xIndex).trim();
                return Integer.parseInt(amountStr);
            }
        } catch (Exception e) {
        }
        return 0;
    }
    public void addMessage(String message) {
        this.messages.add(message);
    }
    public List<String> getMessagesSnapshot() {
        return new ArrayList<>(this.messages);
    }
    private int countMessageLines(Minecraft client, int width) {
        int lineCount = 1;
        if (messages.isEmpty()) return lineCount;
        if (messages.size() == 1 && messages.get(0).equals("Craftable -")) return lineCount;
        for (String message : new ArrayList<>(messages)) {
            if (message.equals("Craftable -")) continue;
            String[] words = message.split(" ");
            StringBuilder line = new StringBuilder();
            int linesInMessage = 1;
            for (String word : words) {
                if (client.font.width(line.toString() + word) > width - 15) {
                    linesInMessage++;
                    line = new StringBuilder(message.startsWith("   ") ? "      " : "   ").append(word).append(" ");
                } else {
                    line.append(word).append(" ");
                }
            }
            lineCount += linesInMessage;
        }
        return lineCount;
    }

    public boolean isShowRemaining() {
        return showRemaining;
    }
    public void setShowRemaining(boolean showRemaining) {
        this.showRemaining = showRemaining;
        saveConfiguration();
    }
    public boolean isShowTotal() { return showTotal; }
    public void setShowTotal(boolean showTotal) {
        this.showTotal = showTotal;
        requestRefresh();
        saveConfiguration();
    }
    public boolean isNotifications() { return notifications; }
    public void setNotifications(boolean notifications) {
        this.notifications = notifications;
        saveConfiguration();
    }
    public boolean isAutoRemove() { return autoRemove; }
    public void setAutoRemove(boolean autoRemove) {
        this.autoRemove = autoRemove;
        requestRefresh();
        saveConfiguration();
    }
    public int getMaxRecipes() { return maxRecipes; }
    public void setMaxRecipes(int maxRecipes) {
        this.maxRecipes = clampMaxRecipes(maxRecipes);
        saveConfiguration();
    }
    private static int clampMaxRecipes(int value) {
        return Math.max(1, Math.min(MAX_RECIPES_LIMIT, value));
    }

    private static final int DONE_GREEN = 0xFF6EFF6E;
    private static final int PARTIAL_ORANGE = 0xFFFFA040;
    private static final int MISSING_RED = 0xFFFF6B6B;

    /** The number shown next to a node: what is still missing, or the full amount the recipe needs. */
    public static int displayedAmount(RecipeManager.RecipeNode node, boolean showRemaining) {
        return showRemaining ? node.amount : node.required;
    }

    /** Green when complete; with remaining mode on, orange when partly gathered and red when none yet. */
    public static int progressColor(RecipeManager.RecipeNode node, boolean showRemaining) {
        if (node.amount <= 0) return DONE_GREEN;
        if (showRemaining && node.amount < node.required) return PARTIAL_ORANGE;
        return MISSING_RED;
    }

    /** Translucent version of {@link #progressColor} for node borders. */
    public static int progressBorderColor(RecipeManager.RecipeNode node, boolean showRemaining) {
        return (progressColor(node, showRemaining) & 0x00FFFFFF) | 0x88000000;
    }

    public static String makePathKey(String parent, String name) {
        if (parent == null || parent.isEmpty()) return name == null ? "" : name;
        if (name == null || name.isEmpty()) return parent;
        return parent + ">" + name;
    }
}

