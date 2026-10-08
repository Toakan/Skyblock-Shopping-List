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
    /** "Forging -" lines: forge slots making something the shopping list needs. */
    private final List<String> forgingLines = new CopyOnWriteArrayList<>();
    /** Update thread only: every item name in the current shopping-list trees. */
    private Set<String> listItemNames = Set.of();
    /** Update thread only: forge version and minute the forging lines were built for. */
    private long forgingVersion = -1;
    private long forgingMinute = -1;
    private volatile boolean showTotal = true;
    private volatile boolean notifications = true;
    private volatile boolean autoRemove = true;
    private volatile boolean staleSackWarning = true;
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
    /** Render thread only: row size and panel width of the HUD being drawn. */
    private int currentNodeLineHeight = 16;
    private int currentRowGap = 0;
    private float currentTreeScale = 1.0f;
    private int currentPanelWidth = 250;

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
    /** HUD heading. The recipes themselves are the top-level rows underneath. */
    public String getTitle() {
        return "Shopping List";
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
        config.showTotal = showTotal;
        config.notifications = notifications;
        config.autoRemove = autoRemove;
        config.staleSackWarning = staleSackWarning;
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
        // "Show remaining" became Settings > Appearance > Amount format; carry an old OFF over once.
        if (Boolean.FALSE.equals(config.showRemaining) && !HudStyle.isSaved()) {
            HudStyle.get().amountFormat = HudStyle.AmountFormat.REQUIRED;
            HudStyle.save();
        }
        if (config.showTotal != null) this.showTotal = config.showTotal;
        if (config.notifications != null) this.notifications = config.notifications;
        if (config.autoRemove != null) this.autoRemove = config.autoRemove;
        if (config.staleSackWarning != null) this.staleSackWarning = config.staleSackWarning;
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
        showTotal = true;
        notifications = true;
        autoRemove = true;
        staleSackWarning = true;
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
        /** Read only, for configs written before Settings > Appearance existed. */
        Boolean showRemaining;
        Boolean showTotal;
        Boolean notifications;
        Boolean autoRemove;
        Boolean staleSackWarning;
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
            if (version != computedVersion) {
                computedVersion = version;
                updateRecipeData();
            }
            // Forge countdowns move every minute even when nothing else changes.
            long minute = System.currentTimeMillis() / 60_000;
            if (ForgeTracker.getVersion() != forgingVersion || minute != forgingMinute) {
                forgingVersion = ForgeTracker.getVersion();
                forgingMinute = minute;
                updateForgingLines();
            }
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
            listItemNames = Set.of();
            forgingLines.clear();
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

        Set<String> itemNames = new HashSet<>();
        for (RecipeManager.RecipeNode tree : response.trees) collectNames(tree, itemNames);
        listItemNames = itemNames;
        updateForgingLines();
    }

    private static void collectNames(RecipeManager.RecipeNode node, Set<String> out) {
        out.add(ItemNames.normalize(node.name));
        if (node.ingredients != null) {
            for (RecipeManager.RecipeNode child : node.ingredients) collectNames(child, out);
        }
    }

    /** Forge slots making something on the list, grouped by item and finish minute; soonest first. */
    private void updateForgingLines() {
        long now = System.currentTimeMillis();
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, ForgeTracker.Entry> firstOf = new LinkedHashMap<>();
        List<ForgeTracker.Entry> relevant = new ArrayList<>();
        for (ForgeTracker.Entry entry : ForgeTracker.getEntries()) {
            if (listItemNames.contains(ItemNames.normalize(entry.name))) relevant.add(entry);
        }
        relevant.sort(java.util.Comparator.comparingLong(e -> e.endsAt));
        for (ForgeTracker.Entry entry : relevant) {
            String key = entry.name + "|" + ForgeTracker.formatRemaining(entry.endsAt, now);
            counts.computeIfAbsent(key, k -> new int[1])[0] += entry.count;
            firstOf.putIfAbsent(key, entry);
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            ForgeTracker.Entry entry = firstOf.get(e.getKey());
            lines.add("   " + e.getValue()[0] + "× " + entry.name + " - " + ForgeTracker.formatRemaining(entry.endsAt, now));
        }
        forgingLines.clear();
        forgingLines.addAll(lines);
    }

    /** Client-side toast; never sent anywhere. Off when notifications are disabled or outside SkyBlock. */
    private void notifyPlayer(SystemToast.SystemToastId id, String title, String message) {
        if (!notifications || !SkyblockDetector.isOnSkyblock()) return;
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> SystemToast.add(client.gui.toastManager(), id,
            Component.literal(title).withStyle(ChatFormatting.GOLD), Component.literal(message)));
    }

    private void render(GuiGraphicsExtractor context) {
        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        renderAt(context, widgetX, widgetY, widgetWidth, Math.min(screenHeight - 40, widgetHeight));
    }

    /**
     * Draws the HUD panel at the given place, as tall as its content needs up to {@code panelMaxHeight}. The
     * Move HUD preview uses this too, so it always matches the real HUD. Render thread only.
     */
    public void renderAt(GuiGraphicsExtractor context, int panelX, int panelY, int panelWidth, int panelMaxHeight) {
        RecipeManager.RecipeNode root = this.recipeTree;
        if (root == null) return;
        HudStyle style = HudStyle.get();
        Minecraft client = Minecraft.getInstance();
        int screenHeight = client.getWindow().getGuiScaledHeight();
        currentPanelWidth = panelWidth;

        int rowStep = style.rowHeight + style.rowGap;
        int visibleLines = 0;
        for (RecipeManager.RecipeNode top : root.ingredients) visibleLines += countVisibleRecipeTreeLines(top, LIST_KEY);
        int lineUnit = Math.round(10 * style.textScale);
        int messageLinesRaw = countMessageLines(client, panelWidth);
        int availableForMessagesMax = Math.max(0, Math.min((int)(screenHeight * 0.4), panelMaxHeight - 20 - visibleLines * rowStep - 15));
        int messageSectionHeightEst = messageLinesRaw > 0 ? Math.min(messageLinesRaw * lineUnit + 20, availableForMessagesMax) : 0;

        // Rows shrink (down to 6px) when the panel can't fit them at the chosen height.
        int availableTreeHeight = Math.max(0, panelMaxHeight - 22 - (messageSectionHeightEst > 0 ? (messageSectionHeightEst + 15) : 0));
        int safeLines = Math.max(1, visibleLines);
        int step = Math.min(rowStep, Math.max(6, availableTreeHeight / safeLines));
        float fit = (float) step / rowStep;
        currentNodeLineHeight = Math.max(6, Math.round(style.rowHeight * fit));
        currentRowGap = Math.round(style.rowGap * fit);
        currentTreeScale = currentNodeLineHeight / 16.0f;
        int treeHeightActual = safeLines * (currentNodeLineHeight + currentRowGap);

        int availableForMessages = Math.max(0, Math.min((int)(screenHeight * 0.4), panelMaxHeight - 20 - treeHeightActual - 15));
        int messageSectionHeight = messageLinesRaw > 0 ? Math.min(messageLinesRaw * lineUnit + 20, availableForMessages) : 0;
        int panelHeight = Math.min(panelMaxHeight, 20 + treeHeightActual + messageSectionHeight + 15);

        context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, style.panelBackground);
        context.fill(panelX, panelY, panelX + panelWidth, panelY + 20, style.headerBackground);
        for (int i = 0; i < style.panelBorderWidth; i++) {
            context.outline(panelX - i, panelY - i, panelWidth + i * 2, panelHeight + i * 2, style.panelBorder);
        }

        Component title = style.text(getTitle(), style.titleText, true);
        int titleWidth = client.font.width(title);
        int maxTitleWidth = Math.max(20, panelWidth - 10);
        float titleScale = Math.min(style.textScale, (float) maxTitleWidth / Math.max(1, titleWidth));
        context.pose().pushMatrix();
        context.pose().translate(panelX + (panelWidth - titleWidth * titleScale) / 2f, panelY + (20 - 8 * titleScale) / 2f);
        context.pose().scale(titleScale, titleScale);
        context.text(client.font, title, 0, 0, 0xFFFFFFFF, style.textShadow);
        context.pose().popMatrix();
        context.fill(panelX, panelY + 19, panelX + panelWidth, panelY + 20, style.divider);

        int treeEndY = panelY + 22;
        for (RecipeManager.RecipeNode top : root.ingredients) {
            treeEndY = renderRecipeTree(context, top, panelX + style.padding, treeEndY, 0, LIST_KEY);
        }

        if (messageSectionHeight > 0) {
            context.fill(panelX, treeEndY, panelX + panelWidth, treeEndY + 1, style.divider);
            drawMessages(context, panelX, treeEndY + 6, panelWidth, panelY + panelHeight - 5);
        }
    }

    private int countVisibleRecipeTreeLines(RecipeManager.RecipeNode node, String pathKey) {
        if (node == null) return 0;
        int count = 1;
        // Path-based key keeps expansion stable regardless of amounts.
        String nodeKey = makePathKey(pathKey, node.name);
        if (node.ingredients != null && !node.ingredients.isEmpty() && expandedNodes.getOrDefault(nodeKey, false)) {
            for (RecipeManager.RecipeNode child : node.ingredients) {
                count += countVisibleRecipeTreeLines(child, nodeKey);
            }
        }
        return count;
    }

    private int renderRecipeTree(GuiGraphicsExtractor context, RecipeManager.RecipeNode node, int x, int y, int level, String pathKey) {
        if (node == null) return y;
        HudStyle style = HudStyle.get();
        Minecraft client = Minecraft.getInstance();
        int unitIndent = Math.max(2, Math.round(style.indent * currentTreeScale));
        int indent = level * unitIndent;
        boolean hasEnough = node.amount <= 0 && node.toCraft <= 0;
        String nodeKey = makePathKey(pathKey, node.name);
        boolean isExpanded = expandedNodes.getOrDefault(nodeKey, false);
        boolean hasChildren = node.ingredients != null && !node.ingredients.isEmpty();
        boolean showRemaining = isShowRemaining();
        int nodeHeight = currentNodeLineHeight;
        int nodeWidth = Math.max(100, currentPanelWidth - 2 * style.padding) - indent;
        int statusColor = progressColor(node, showRemaining);

        if (style.showRowBoxes) {
            context.fill(x + indent, y, x + indent + nodeWidth, y + nodeHeight, style.rowBackground);
            int border = progressBorderColor(node, showRemaining);
            for (int i = 0; i < style.rowBorderWidth; i++) {
                context.outline(x + indent + i, y + i, nodeWidth - 2 * i, nodeHeight - 2 * i, border);
            }
        }

        float rowScale = currentTreeScale * style.textScale;
        int iconOffset = hasChildren ? Math.max(14, Math.round(25 * currentTreeScale)) : Math.max(6, Math.round(10 * currentTreeScale));
        if (hasChildren) {
            drawScaled(context, style.text(isExpanded ? "▼" : "▶", style.itemText, false),
                x + indent + Math.max(3, Math.round(5 * currentTreeScale)), textY(y, nodeHeight, rowScale), rowScale, style.textShadow);
        }

        int nameColor = level == 0 ? style.rootText : hasEnough ? style.itemText : statusColor;
        Component mark = style.showMarks ? style.text(hasEnough || node.amount <= 0 ? "✔ " : "✖ ", statusColor, false) : Component.empty();
        Component amount = style.text(amountText(node) + " ", statusColor, false);
        Component name = style.text(node.name, nameColor, level == 0 && style.boldRootNames);
        int markWidth = client.font.width(mark);
        int amountWidth = client.font.width(amount);
        int totalTextWidth = markWidth + amountWidth + client.font.width(name);
        int maxTextWidth = Math.max(10, nodeWidth - iconOffset);
        float textScale = Math.min(rowScale, (float) maxTextWidth / Math.max(1, totalTextWidth));

        context.pose().pushMatrix();
        context.pose().translate(x + indent + iconOffset, textY(y, nodeHeight, textScale));
        context.pose().scale(textScale, textScale);
        context.text(client.font, mark, 0, 0, 0xFFFFFFFF, style.textShadow);
        context.text(client.font, amount, markWidth, 0, 0xFFFFFFFF, style.textShadow);
        context.text(client.font, name, markWidth + amountWidth, 0, 0xFFFFFFFF, style.textShadow);
        context.pose().popMatrix();
        y += nodeHeight + currentRowGap;

        if (hasChildren && isExpanded) {
            for (RecipeManager.RecipeNode child : node.ingredients) {
                if (style.showTreeLines) {
                    int lineStartX = x + indent + Math.max(4, Math.round(6 * currentTreeScale));
                    int childIndentX = x + indent + unitIndent;
                    int vertLen = Math.max(3, nodeHeight / 2);
                    context.fill(lineStartX, y - currentRowGap, lineStartX + 1, y + vertLen, style.treeLines);
                    context.fill(lineStartX, y + vertLen, childIndentX, y + vertLen + 1, style.treeLines);
                }
                y = renderRecipeTree(context, child, x, y, level + 1, nodeKey);
            }
        }
        return y;
    }

    /** Top of text of the given scale, centred in a row. */
    private static float textY(int rowY, int rowHeight, float scale) {
        return rowY + Math.max(0, (rowHeight - 8 * scale) / 2f);
    }

    private static void drawScaled(GuiGraphicsExtractor context, Component text, float x, float y, float scale, boolean shadow) {
        context.pose().pushMatrix();
        context.pose().translate(x, y);
        context.pose().scale(scale, scale);
        context.text(Minecraft.getInstance().font, text, 0, 0, 0xFFFFFFFF, shadow);
        context.pose().popMatrix();
    }

    private void drawMessages(GuiGraphicsExtractor context, int x, int y, int width, int maxY) {
        Minecraft client = Minecraft.getInstance();
        HudStyle style = HudStyle.get();
        int baseHeader = 13;
        int baseLine = 10;
        int availableHeight = Math.max(0, maxY - y);
        int lines = countMessageLines(client, width);
        int desiredHeight = Math.round((baseHeader + Math.max(0, (lines - 1) * baseLine)) * style.textScale);
        float fit = desiredHeight > 0 ? Math.min(1.0f, Math.max(0.4f, (float) availableHeight / (float) desiredHeight)) : 1.0f;
        float scale = fit * style.textScale;

        List<String> craftable = new ArrayList<>(messages);
        craftable.remove("Craftable -");
        craftable.sort((a, b) -> Integer.compare(extractAmount(b), extractAmount(a)));
        y = drawSection(context, "Craftable -", craftable, x, y, width, maxY, scale);
        List<String> forging = new ArrayList<>(forgingLines);
        if (y >= 0 && !forging.isEmpty()) {
            drawSection(context, "Forging -", forging, x, y + Math.round(3 * scale), width, maxY, scale);
        }
    }

    /** Draws a centred header and its wrapped, centred lines. Returns the next y, or -1 when out of room. */
    private int drawSection(GuiGraphicsExtractor context, String header, List<String> lines, int x, int y, int width,
                            int maxY, float scale) {
        Minecraft client = Minecraft.getInstance();
        HudStyle style = HudStyle.get();
        int baseHeader = 13;
        int baseLine = 10;
        if (y + Math.round(baseLine * scale) > maxY) return -1;
        drawCentred(context, style.text(header, style.sectionHeader, true), x, y, width, scale);
        y += Math.round(baseHeader * scale);

        int unscaledWrapWidth = Math.max(10, (int) Math.floor((width - 15) / Math.max(0.01f, scale)));
        for (String message : lines) {
            int textColor = message.endsWith(" - Ready") ? style.done : style.sectionText;
            String[] words = message.split(" ");
            StringBuilder line = new StringBuilder();
            for (String word : words) {
                if (client.font.width(style.text(line + word, textColor, false)) > unscaledWrapWidth) {
                    if (y + Math.round(baseLine * scale) > maxY) return -1;
                    drawCentred(context, style.text(line.toString().trim(), textColor, false), x, y, width, scale);
                    y += Math.round(baseLine * scale);
                    line = new StringBuilder(message.startsWith("   ") ? "      " : "   ").append(word).append(" ");
                } else {
                    line.append(word).append(" ");
                }
            }
            if (line.length() > 0) {
                if (y + Math.round((baseLine - 1) * scale) > maxY) return -1;
                drawCentred(context, style.text(line.toString().trim(), textColor, false), x, y, width, scale);
                y += Math.round((baseLine - 1) * scale);
            }
        }
        return y;
    }

    private static void drawCentred(GuiGraphicsExtractor context, Component text, int x, int y, int width, float scale) {
        Minecraft client = Minecraft.getInstance();
        context.pose().pushMatrix();
        context.pose().translate(x + (width - client.font.width(text) * scale) / 2f, y);
        context.pose().scale(scale, scale);
        context.text(client.font, text, 0, 0, 0xFFFFFFFF, HudStyle.get().textShadow);
        context.pose().popMatrix();
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
        // Header + one line per forging slot group.
        int forging = forgingLines.isEmpty() ? 0 : forgingLines.size() + 1;
        return countCraftableLines(client, width) + forging;
    }

    private int countCraftableLines(Minecraft client, int width) {
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

    /** False when the amount format shows the full required amounts (colours then skip "partly gathered"). */
    public boolean isShowRemaining() {
        return HudStyle.get().amountFormat != HudStyle.AmountFormat.REQUIRED;
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
    public boolean isStaleSackWarning() { return staleSackWarning; }
    public void setStaleSackWarning(boolean staleSackWarning) {
        this.staleSackWarning = staleSackWarning;
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

    /**
     * The amount shown next to a node, per Settings > Appearance > Amount format: still to get ("3×"),
     * held of needed ("83/5,120"), or the full amount the recipe needs ("6×").
     */
    public static String amountText(RecipeManager.RecipeNode node) {
        return switch (HudStyle.get().amountFormat) {
            case REMAINING -> (node.amount + node.toCraft) + "×";
            case REQUIRED -> node.required + "×";
            case HAVE_NEED -> String.format(Locale.ROOT, "%,d/%,d",
                Math.max(0, node.required - node.amount - node.toCraft), node.required);
        };
    }

    /**
     * Green when held; yellow when the rest can be crafted from materials you have; with remaining mode on,
     * orange when partly gathered and red when none yet.
     */
    public static int progressColor(RecipeManager.RecipeNode node, boolean showRemaining) {
        HudStyle style = HudStyle.get();
        if (node.amount <= 0) return node.toCraft > 0 ? style.craftable : style.done;
        if (showRemaining && node.amount < node.required) return style.partial;
        return style.missing;
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

