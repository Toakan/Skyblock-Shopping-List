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
    /** Craftable / Forging panels, used when Appearance puts them in their own panel. */
    private PanelRect craftablePanel = new PanelRect(270, 40, 180, 150);
    private PanelRect forgingPanel = new PanelRect(270, 200, 180, 120);
    private Map<String, Boolean> expandedNodes = new ConcurrentHashMap<>();
    private final List<String> messages = new CopyOnWriteArrayList<>();
    /** "Forging -" lines: forge slots making something the shopping list needs. */
    private final List<String> forgingLines = new CopyOnWriteArrayList<>();
    /** Update thread only: every item name in the current shopping-list trees. */
    private Set<String> listItemNames = Set.of();
    /** Update thread only: forge version and minute the forging lines were built for. */
    private long forgingVersion = -1;
    private long forgingMinute = -1;
    /** Update thread only: a recipe has everything but something in its tree is still cooking in the Forge. */
    private boolean waitingOnForge = false;
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
    /**
     * Moves an entry up (negative) or down (positive) the list. Order is priority: entries higher up get shared
     * stock first.
     */
    public synchronized void moveEntry(String recipe, int delta) {
        for (int i = 0; i < shoppingList.size(); i++) {
            if (!shoppingList.get(i).recipe.equals(recipe)) continue;
            int target = i + delta;
            if (target < 0 || target >= shoppingList.size()) return;
            ShoppingListEntry moved = shoppingList.get(i);
            shoppingList.set(i, shoppingList.get(target));
            shoppingList.set(target, moved);
            listChanged();
            return;
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
        config.craftablePanel = craftablePanel;
        config.forgingPanel = forgingPanel;
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
        if (config.craftablePanel != null) this.craftablePanel = config.craftablePanel;
        if (config.forgingPanel != null) this.forgingPanel = config.forgingPanel;
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
        craftablePanel = new PanelRect(270, 40, 180, 150);
        forgingPanel = new PanelRect(270, 200, 180, 120);
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
        PanelRect craftablePanel;
        PanelRect forgingPanel;
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
                boolean forgeChanged = ForgeTracker.getVersion() != forgingVersion;
                forgingVersion = ForgeTracker.getVersion();
                forgingMinute = minute;
                // A held-back "Ready to craft" may be due now that time passed or the forge changed.
                if (waitingOnForge || forgeChanged) {
                    updateRecipeData();
                } else {
                    updateForgingLines();
                }
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
        Map<String, Integer> cooking = cookingCounts();
        // Finished (not cooking) stock left per item, shared out in list order like the shopping list does.
        Map<String, Integer> finishedLeft = new HashMap<>();
        waitingOnForge = false;
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
            // Forge items count as owned while cooking, but can't be used until they are done: hold the pop-up
            // only if this recipe needs more of an item than is already finished.
            if (!cooking.isEmpty() && needsCooking(tree, cooking, finishedLeft) && ready) {
                ready = false;
                waitingOnForge = true;
            }
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

    /** How many of each Forge item (by normalised name) are still cooking. */
    private static Map<String, Integer> cookingCounts() {
        long now = System.currentTimeMillis();
        Map<String, Integer> out = new HashMap<>();
        for (ForgeTracker.Entry entry : ForgeTracker.getEntries()) {
            if (entry.endsAt > now) out.merge(ItemNames.normalize(entry.name), entry.count, Integer::sum);
        }
        return out;
    }

    /**
     * True if the tree takes more of a cooking item from stock than is already finished. Uses up
     * {@code finishedLeft} as it goes (every node is visited), so later recipes only get what is left. The root
     * is skipped: it never draws on stock of itself.
     */
    private boolean needsCooking(RecipeManager.RecipeNode root, Map<String, Integer> cooking, Map<String, Integer> finishedLeft) {
        boolean short_ = false;
        if (root.ingredients != null) {
            for (RecipeManager.RecipeNode child : root.ingredients) {
                if (useStock(child, cooking, finishedLeft)) short_ = true;
            }
        }
        return short_;
    }

    private boolean useStock(RecipeManager.RecipeNode node, Map<String, Integer> cooking, Map<String, Integer> finishedLeft) {
        boolean short_ = false;
        String key = ItemNames.normalize(node.name);
        Integer cookingCount = cooking.get(key);
        if (cookingCount != null) {
            // What this node takes from items already held (not missing, not still to craft).
            int used = Math.max(0, node.required - node.amount - node.toCraft);
            int left = finishedLeft.computeIfAbsent(key,
                k -> Math.max(0, resourcesManager.getResourceByName(node.name) - cookingCount));
            if (used > left) short_ = true;
            finishedLeft.put(key, Math.max(0, left - used));
        }
        if (node.ingredients != null) {
            for (RecipeManager.RecipeNode child : node.ingredients) {
                if (useStock(child, cooking, finishedLeft)) short_ = true;
            }
        }
        return short_;
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

    /** The HUD panels: the shopping list, and Craftable / Forging when set to their own panel. */
    public enum Panel { MAIN, CRAFTABLE, FORGING }

    /** Position (GUI pixels) and size (HUD units, before the HUD scale) of one panel. */
    public static final class PanelRect {
        public int x;
        public int y;
        public int width;
        public int height;

        public PanelRect(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    /** One block of centred/aligned lines under a header (Craftable, Forging). */
    private record Section(String header, List<String> lines, float scale, HudStyle.Align align,
                           int headerColor, int textColor) {}

    private static final int TITLE_BAR = 20;

    /** HUD units -> GUI pixels. Ignores Minecraft's GUI Scale unless the style says to follow it. */
    public static float scaleFactor() {
        HudStyle style = HudStyle.get();
        float scale = style.hudScale / 100f;
        if (style.followGuiScale) return scale;
        int guiScale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        return scale * 2f / guiScale;
    }

    public PanelRect getPanelRect(Panel panel) {
        return switch (panel) {
            case MAIN -> new PanelRect(widgetX, widgetY, widgetWidth, widgetHeight);
            case CRAFTABLE -> copy(craftablePanel);
            case FORGING -> copy(forgingPanel);
        };
    }

    public void setPanelRect(Panel panel, int x, int y, int width, int height) {
        PanelRect rect = new PanelRect(x, y, Math.max(minWidth(panel), width), Math.max(minHeight(panel), height));
        switch (panel) {
            case MAIN -> {
                widgetX = rect.x;
                widgetY = rect.y;
                widgetWidth = rect.width;
                widgetHeight = rect.height;
            }
            case CRAFTABLE -> craftablePanel = rect;
            case FORGING -> forgingPanel = rect;
        }
        saveConfiguration();
    }

    /** Puts every panel back to its starting place (sizes are kept): own panels go right of the main one. */
    public void resetPanelPositions() {
        float scale = scaleFactor();
        widgetX = 10;
        widgetY = 40;
        int sideX = widgetX + Math.round(widgetWidth * scale) + 10;
        craftablePanel = new PanelRect(sideX, widgetY, craftablePanel.width, craftablePanel.height);
        forgingPanel = new PanelRect(sideX, widgetY + Math.round(craftablePanel.height * scale) + 10,
            forgingPanel.width, forgingPanel.height);
        saveConfiguration();
    }

    public static int minWidth(Panel panel) {
        return panel == Panel.MAIN ? 180 : 80;
    }

    public static int minHeight(Panel panel) {
        return panel == Panel.MAIN ? 120 : 40;
    }

    private static PanelRect copy(PanelRect r) {
        return new PanelRect(r.x, r.y, r.width, r.height);
    }

    /** Panels the current style draws: the main one, plus Craftable / Forging when they have their own. */
    public static List<Panel> activePanels() {
        HudStyle style = HudStyle.get();
        List<Panel> panels = new ArrayList<>();
        panels.add(Panel.MAIN);
        if (style.showCraftable && style.craftablePlacement == HudStyle.Placement.OWN_PANEL) panels.add(Panel.CRAFTABLE);
        if (style.showForging && style.forgingPlacement == HudStyle.Placement.OWN_PANEL) panels.add(Panel.FORGING);
        return panels;
    }

    private void render(GuiGraphicsExtractor context) {
        for (Panel panel : activePanels()) {
            PanelRect rect = getPanelRect(panel);
            renderPanel(context, panel, rect.x, rect.y, rect.width, rect.height, false);
        }
    }

    /**
     * Draws one panel with its top-left at (x, y) in GUI pixels; width and max height are HUD units. The panel is
     * as tall as its content up to the max height; anything below is cut off and marked with "…". The Move HUD
     * preview draws through here too, so it always matches. Returns the drawn height in HUD units (0 if nothing
     * was drawn). Render thread only.
     */
    public int renderPanel(GuiGraphicsExtractor context, Panel panel, int x, int y, int width, int maxHeight, boolean preview) {
        HudStyle style = HudStyle.get();
        RecipeManager.RecipeNode root = this.recipeTree;
        List<Section> sections = sectionsFor(panel, style);
        String title;
        int rowsHeight = 0;
        switch (panel) {
            case MAIN -> {
                if (root == null) return 0;
                title = getTitle();
                int visibleLines = 0;
                for (RecipeManager.RecipeNode top : root.ingredients) visibleLines += countVisibleRecipeTreeLines(top, LIST_KEY);
                rowsHeight = 2 + visibleLines * (style.rowHeight + style.rowGap);
            }
            case CRAFTABLE -> title = "Craftable";
            default -> title = "Forging";
        }
        boolean empty = panel != Panel.MAIN && sections.stream().allMatch(s -> s.lines().isEmpty());
        if (empty && !preview) return 0;

        Minecraft client = Minecraft.getInstance();
        boolean ownPanel = panel != Panel.MAIN;
        int contentWidth = width - 2 * style.padding;
        List<List<String>> wrapped = new ArrayList<>();
        int sectionsHeight = 0;
        for (Section section : sections) {
            List<String> lines = wrap(client, style, section, contentWidth);
            wrapped.add(lines);
            sectionsHeight += sectionHeight(section, lines.size(), ownPanel);
        }
        // With rounded corners wider than the padding, content stops above the bottom corners so it can't cover them.
        int cornerClip = Math.max(0, style.panelRadius - style.padding) > 0 ? style.panelRadius : 0;
        int naturalHeight = TITLE_BAR + rowsHeight + sectionsHeight + (empty ? 14 : 4) + cornerClip;
        int panelHeight = Math.min(maxHeight, naturalHeight);

        float scale = scaleFactor();
        context.pose().pushMatrix();
        context.pose().translate(x, y);
        context.pose().scale(scale, scale);

        RoundedBox.fill(context, 0, 0, width, panelHeight, style.panelRadius, style.panelBackground);
        RoundedBox.fill(context, 0, 0, width, TITLE_BAR, style.panelRadius, true, false, style.headerBackground);
        for (int i = 0; i < style.panelBorderWidth; i++) {
            // Outer border rings grow outward, so their radius grows with them to stay parallel.
            RoundedBox.outline(context, -i, -i, width + i * 2, panelHeight + i * 2,
                style.panelRadius > 0 ? style.panelRadius + i : 0, style.panelBorder);
        }
        Component titleText = style.text(title, style.titleText, true);
        int titleWidth = client.font.width(titleText);
        float titleScale = Math.min(style.titleScale, (float) (width - 10) / Math.max(1, titleWidth));
        drawScaled(context, titleText, alignedX(style.titleAlign, width, 5, titleWidth * titleScale),
            (TITLE_BAR - 8 * titleScale) / 2f, titleScale, style.textShadow);
        context.fill(0, TITLE_BAR - 1, width, TITLE_BAR, style.divider);

        context.enableScissor(0, TITLE_BAR, width, panelHeight - cornerClip);
        int cursorY = TITLE_BAR + 2;
        if (panel == Panel.MAIN) {
            currentPanelWidth = width;
            currentNodeLineHeight = style.rowHeight;
            currentRowGap = style.rowGap;
            currentTreeScale = style.rowHeight / 16.0f;
            for (RecipeManager.RecipeNode top : root.ingredients) {
                cursorY = renderRecipeTree(context, top, style.padding, cursorY, 0, LIST_KEY);
            }
        }
        if (empty) {
            Component hint = style.text("(nothing yet)", style.itemText, false);
            drawScaled(context, hint, (width - client.font.width(hint)) / 2f, cursorY + 2, 1f, style.textShadow);
        }
        for (int i = 0; i < sections.size(); i++) {
            cursorY = drawSection(context, sections.get(i), wrapped.get(i), width, cursorY, ownPanel);
        }
        context.disableScissor();

        if (naturalHeight > panelHeight) {
            // Cut off: tell the player there is more (raise the max height in Move HUD, or collapse rows).
            Component more = style.text("…", style.itemText, true);
            RoundedBox.fill(context, 0, panelHeight - 9, width, 9, style.panelRadius, false, true, style.panelBackground);
            drawScaled(context, more, (width - client.font.width(more)) / 2f, panelHeight - 9, 1f, style.textShadow);
        }
        context.pose().popMatrix();
        return panelHeight;
    }

    /** The Craftable / Forging blocks this panel shows. */
    private List<Section> sectionsFor(Panel panel, HudStyle style) {
        List<Section> sections = new ArrayList<>();
        boolean craftableHere = style.showCraftable
            && (style.craftablePlacement == HudStyle.Placement.OWN_PANEL ? panel == Panel.CRAFTABLE : panel == Panel.MAIN);
        boolean forgingHere = style.showForging
            && (style.forgingPlacement == HudStyle.Placement.OWN_PANEL ? panel == Panel.FORGING : panel == Panel.MAIN);
        if (craftableHere) {
            List<String> craftable = new ArrayList<>();
            for (String message : messages) {
                if (!message.equals("Craftable -")) craftable.add(message.trim());
            }
            craftable.sort((a, b) -> Integer.compare(extractAmount(b), extractAmount(a)));
            if (panel != Panel.MAIN || !craftable.isEmpty()) {
                sections.add(new Section("Craftable", craftable, style.craftableScale, style.craftableAlign,
                    style.sectionHeader, style.sectionText));
            }
        }
        if (forgingHere) {
            List<String> forging = new ArrayList<>();
            for (String line : forgingLines) forging.add(line.trim());
            if (panel != Panel.MAIN || !forging.isEmpty()) {
                sections.add(new Section("Forging", forging, style.forgingScale, style.forgingAlign,
                    style.forgingHeader, style.forgingText));
            }
        }
        return sections;
    }

    /** Height of a section: divider and header inside the main panel (own panels have a title bar), then lines. */
    private static int sectionHeight(Section section, int lineCount, boolean ownPanel) {
        int header = ownPanel ? 0 : 6 + Math.round(13 * section.scale());
        return header + lineCount * Math.round(10 * section.scale()) + 2;
    }

    /** Splits each line into pieces that fit the content width at the section's scale. */
    private static List<String> wrap(Minecraft client, HudStyle style, Section section, int contentWidth) {
        int maxWidth = Math.max(10, (int) Math.floor(contentWidth / Math.max(0.01f, section.scale())));
        List<String> out = new ArrayList<>();
        for (String message : section.lines()) {
            StringBuilder line = new StringBuilder();
            for (String word : message.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (!line.isEmpty() && client.font.width(style.text(candidate, 0, false)) > maxWidth) {
                    out.add(line.toString());
                    line = new StringBuilder("  ").append(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            if (!line.isEmpty()) out.add(line.toString());
        }
        return out;
    }

    private int drawSection(GuiGraphicsExtractor context, Section section, List<String> lines, int width, int y, boolean ownPanel) {
        Minecraft client = Minecraft.getInstance();
        HudStyle style = HudStyle.get();
        float scale = section.scale();
        if (!ownPanel) {
            context.fill(0, y, width, y + 1, style.divider);
            y += 5;
            Component header = style.text(section.header() + " -", section.headerColor(), true);
            drawScaled(context, header, alignedX(section.align(), width, style.padding, client.font.width(header) * scale),
                y, scale, style.textShadow);
            y += Math.round(13 * scale);
        }
        int lineHeight = Math.round(10 * scale);
        for (String line : lines) {
            int color = line.endsWith(" - Ready") ? style.done : section.textColor();
            Component text = style.text(line, color, false);
            drawScaled(context, text, alignedX(section.align(), width, style.padding, client.font.width(text) * scale),
                y, scale, style.textShadow);
            y += lineHeight;
        }
        return y + 2;
    }

    /** Left edge for text of the given drawn width. */
    private static float alignedX(HudStyle.Align align, int width, int padding, float textWidth) {
        return switch (align) {
            case LEFT -> padding;
            case RIGHT -> width - padding - textWidth;
            case CENTRE -> (width - textWidth) / 2f;
        };
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
            RoundedBox.fill(context, x + indent, y, nodeWidth, nodeHeight, style.rowRadius, style.rowBackground);
            int border = progressBorderColor(node, showRemaining);
            for (int i = 0; i < style.rowBorderWidth; i++) {
                RoundedBox.outline(context, x + indent + i, y + i, nodeWidth - 2 * i, nodeHeight - 2 * i,
                    Math.max(0, style.rowRadius - i), border);
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

