package inventoryreader.ir;

import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
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
    private static final java.lang.reflect.Type SHOPPING_LIST_TYPE = new TypeToken<List<ShoppingListEntry>>() {}.getType();
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
    /** Main panel scale in percent, on top of the HUD scale from Settings. */
    private int widgetScale = 100;
    /** GUI-scaled screen size when the main panel was placed (0 = not recorded yet). */
    private int widgetRefWidth = 0;
    private int widgetRefHeight = 0;
    /** Craftable / Forging panels, used when Appearance puts them in their own panel. */
    private PanelRect craftablePanel = new PanelRect(270, 40, 180, 150);
    private PanelRect forgingPanel = new PanelRect(270, 200, 180, 120);
    private volatile Map<String, Boolean> expandedNodes = new ConcurrentHashMap<>();
    /** Craftable lines; replaced whole (never cleared and refilled) so the HUD never sees an empty list mid-update. */
    private volatile List<String> messages = List.of();
    /** "Forging -" lines: forge slots making something the shopping list needs. */
    private volatile List<String> forgingLines = List.of();
    /** Update thread only: every item name in the current shopping-list trees. */
    private Set<String> listItemNames = Set.of();
    /** Update thread only: forge version and minute the forging lines were built for. */
    private long forgingVersion = -1;
    private long forgingMinute = -1;
    /** Update thread only: Quick Forge / mayor bonus version the forge times were worked out with. */
    private long forgeSpeedVersion = -1;
    /** Update thread only: a recipe has everything but something in its tree is still cooking in the Forge. */
    private boolean waitingOnForge = false;
    private volatile boolean showTotal = true;
    /** What the Total adds up: raw materials, or the direct ingredients of each recipe on the list. */
    public enum TotalMode { RAW, INGREDIENTS }
    private volatile TotalMode totalMode = TotalMode.RAW;
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
                shoppingList.set(i, new ShoppingListEntry(recipe, e.amount + amount, e.startCount, e.isHaveTotal()));
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
                shoppingList.set(i, new ShoppingListEntry(recipe, Math.max(1, amount), e.startCount, e.isHaveTotal()));
                listChanged();
                return;
            }
        }
    }
    /** Switches one entry between Have total (held copies count) and Add more (make this many more). */
    public synchronized void setEntryHaveTotal(String recipe, boolean haveTotal) {
        for (int i = 0; i < shoppingList.size(); i++) {
            ShoppingListEntry e = shoppingList.get(i);
            if (e.recipe.equals(recipe) && e.isHaveTotal() != haveTotal) {
                // Add more counts from now: what is held when switching is the new starting point.
                int start = haveTotal ? e.startCount : resourcesManager.getResourceByName(recipe);
                shoppingList.set(i, new ShoppingListEntry(recipe, e.amount, start, haveTotal));
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
        saveShoppingList();
    }
    /** The list lives with the SkyBlock profile (shopping_list.json in its folder). */
    private synchronized void saveShoppingList() {
        List<ShoppingListEntry> list = getShoppingList();
        JsonFiles.writeAsync(FilePathManager.shoppingListJson(), () -> list);
    }
    private synchronized void loadShoppingList() {
        shoppingList.clear();
        List<ShoppingListEntry> saved = JsonFiles.read(FilePathManager.shoppingListJson(), SHOPPING_LIST_TYPE);
        if (saved == null) return;
        for (ShoppingListEntry e : saved) {
            if (e != null && e.recipe != null && !e.recipe.isBlank()) {
                shoppingList.add(new ShoppingListEntry(e.recipe, Math.max(1, e.amount), e.startCount, e.isHaveTotal()));
            }
        }
    }
    /**
     * Profile switch: runs {@code switchFolder} (which points the data files at the new profile) and loads that
     * profile's list, both under this object's lock so a list save can't land in the wrong folder. Pop-ups are
     * re-baselined so the switch itself doesn't announce anything.
     */
    public synchronized void reloadShoppingList(Runnable switchFolder) {
        switchFolder.run();
        loadShoppingList();
        scheduler.execute(() -> {
            readyEntries.clear();
            achievedEntries.clear();
            baselineSet = false;
        });
        requestRefresh();
    }
    /** HUD heading. The recipes themselves are the top-level rows underneath. */
    public String getTitle() {
        return "Shopping List";
    }
    /** The synthetic root the HUD draws (children: Total, then one tree per recipe), or null. */
    public RecipeManager.RecipeNode getDisplayRoot() {
        return recipeTree;
    }
    public boolean isNodeExpanded(String nodeKey) {
        Boolean result = expandedNodes.getOrDefault(nodeKey, false);
        return result != null ? result : false;
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
    public synchronized void saveConfiguration() {
        WidgetConfig config = new WidgetConfig();
        config.enabled = enabled;
        config.widgetX = widgetX;
        config.widgetY = widgetY;
        config.widgetRefWidth = widgetRefWidth;
        config.widgetRefHeight = widgetRefHeight;
        config.widgetWidth = widgetWidth;
        config.widgetHeight = widgetHeight;
        config.widgetScale = widgetScale;
        config.craftablePanel = craftablePanel;
        config.forgingPanel = forgingPanel;
        config.expandedNodes = new HashMap<>(expandedNodes);
        config.showTotal = showTotal;
        config.totalMode = totalMode;
        config.notifications = notifications;
        config.autoRemove = autoRemove;
        config.staleSackWarning = staleSackWarning;
        config.debugLogging = InventoryReader.debugLogging;
        config.maxRecipes = maxRecipes;
        JsonFiles.writeAsync(FilePathManager.WIDGET_CONFIG_JSON, () -> config);
    }
    private void loadConfiguration() {
        WidgetConfig config = JsonFiles.read(FilePathManager.WIDGET_CONFIG_JSON, WidgetConfig.class);
        if (config != null) loadWidgetConfig(config);
        loadShoppingList();
    }
    private void loadWidgetConfig(WidgetConfig config) {
        this.enabled = config.enabled;
        this.widgetX = config.widgetX;
        this.widgetY = config.widgetY;
        this.widgetRefWidth = config.widgetRefWidth;
        this.widgetRefHeight = config.widgetRefHeight;
        if (config.expandedNodes != null) {
            this.expandedNodes = new ConcurrentHashMap<>(config.expandedNodes);
        }
        // "Show remaining" became Settings > Appearance > Amount format; carry an old OFF over once.
        if (Boolean.FALSE.equals(config.showRemaining) && !HudStyle.isSaved()) {
            HudStyle.get().amountFormat = HudStyle.AmountFormat.REQUIRED;
            HudStyle.save();
        }
        if (config.showTotal != null) this.showTotal = config.showTotal;
        if (config.totalMode != null) this.totalMode = config.totalMode;
        if (config.notifications != null) this.notifications = config.notifications;
        if (config.autoRemove != null) this.autoRemove = config.autoRemove;
        if (config.staleSackWarning != null) this.staleSackWarning = config.staleSackWarning;
        InventoryReader.debugLogging = Boolean.TRUE.equals(config.debugLogging);
        if (config.maxRecipes != null) this.maxRecipes = clampMaxRecipes(config.maxRecipes);
        if (config.widgetWidth > 0) this.widgetWidth = config.widgetWidth;
        if (config.widgetHeight > 0) this.widgetHeight = config.widgetHeight;
        if (config.widgetScale != null) this.widgetScale = clampPanelScale(config.widgetScale);
        if (config.craftablePanel != null) this.craftablePanel = config.craftablePanel;
        if (config.forgingPanel != null) this.forgingPanel = config.forgingPanel;
        // Before 4.22 the list was kept in widget_config.json: move it to its own file (with the other data
        // that ProfileManager hands to the first SkyBlock profile seen), then drop it from the config.
        if ((config.shoppingList != null || config.selectedRecipe != null) && !FilePathManager.shoppingListJson().exists()) {
            List<ShoppingListEntry> legacy = new ArrayList<>();
            if (config.shoppingList != null) {
                for (ShoppingListEntry e : config.shoppingList) {
                    if (e != null && e.recipe != null && !e.recipe.isBlank()) {
                        // 4.20.6 had one list-wide switch; entries saved then take its value.
                        boolean haveTotal = e.haveTotal != null ? e.haveTotal : !Boolean.FALSE.equals(config.haveTotal);
                        legacy.add(new ShoppingListEntry(e.recipe, Math.max(1, e.amount), e.startCount, haveTotal));
                    }
                }
            } else {
                // Config from before the shopping list: keep the one recipe it tracked.
                int amount = config.craftAmount != null && config.craftAmount > 0 ? config.craftAmount : 1;
                legacy.add(new ShoppingListEntry(config.selectedRecipe, amount, resourcesManager.getResourceByName(config.selectedRecipe)));
            }
            JsonFiles.write(FilePathManager.shoppingListJson(), legacy);
            saveConfiguration();
        }
    }
    /** Restores defaults after a reset deleted the config file. */
    public synchronized void resetConfiguration() {
        enabled = false;
        shoppingList.clear();
        recipeTree = null;
        widgetX = 10;
        widgetY = 40;
        widgetWidth = 250;
        widgetHeight = 300;
        widgetScale = 100;
        widgetRefWidth = 0;
        widgetRefHeight = 0;
        craftablePanel = new PanelRect(270, 40, 180, 150);
        forgingPanel = new PanelRect(270, 200, 180, 120);
        expandedNodes = new ConcurrentHashMap<>();
        showTotal = true;
        totalMode = TotalMode.RAW;
        notifications = true;
        autoRemove = true;
        staleSackWarning = true;
        InventoryReader.debugLogging = false;
        maxRecipes = 3;
        messages = List.of();
        saveConfiguration();
        requestRefresh();
    }
    private static class WidgetConfig {
        boolean enabled;
        int widgetX;
        int widgetY;
        int widgetRefWidth;
        int widgetRefHeight;
        int widgetWidth;
        int widgetHeight;
        Integer widgetScale;
        PanelRect craftablePanel;
        PanelRect forgingPanel;
        Map<String, Boolean> expandedNodes;
        /** Read only: where the list was kept before 4.22 (now shopping_list.json, per profile). */
        List<ShoppingListEntry> shoppingList;
        /** Read only, for configs written before the shopping list existed. */
        String selectedRecipe;
        /** Read only, for configs written before the shopping list existed. */
        Integer craftAmount;
        // Boxed so configs written before an option existed get its default.
        /** Read only, for configs written before Settings > Appearance existed. */
        Boolean showRemaining;
        Boolean showTotal;
        TotalMode totalMode;
        Boolean notifications;
        Boolean autoRemove;
        /** Read only: the list-wide Have total / Add more switch from 4.20.6, now set per entry. */
        Boolean haveTotal;
        Boolean staleSackWarning;
        Boolean debugLogging;
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
            ForgeSpeed.refreshMayorIfDue();
            long version = resourcesManager.getVersion();
            if (version != computedVersion || ForgeSpeed.getVersion() != forgeSpeedVersion) {
                computedVersion = version;
                forgeSpeedVersion = ForgeSpeed.getVersion();
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
            messages = List.of();
            listItemNames = Set.of();
            forgingLines = List.of();
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
            // Have total: done once you hold the amount. Add more: once you hold that many more than when added.
            boolean achieved = resourcesManager.getResourceByName(entry.recipe)
                >= (entry.isHaveTotal() ? 0 : entry.startCount) + entry.amount;
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

        Map<String, Long> cookingEnds = cookingEnds();
        double forgeMultiplier = ForgeSpeed.multiplier();
        for (RecipeManager.RecipeNode tree : response.trees) setForgeTimes(tree, cookingEnds, forgeMultiplier);

        List<RecipeManager.RecipeNode> tops = new ArrayList<>();
        RecipeManager.RecipeNode total = totalMode == TotalMode.INGREDIENTS ? response.ingredientTotal : response.total;
        if (showTotal && !total.ingredients.isEmpty()) {
            tops.add(total);
            expandedNodes.putIfAbsent(makePathKey(LIST_KEY, total.name), true);
        }
        for (RecipeManager.RecipeNode tree : response.trees) {
            tops.add(tree);
            expandedNodes.putIfAbsent(makePathKey(LIST_KEY, tree.name), true);
        }
        messages = List.copyOf(newMessages);
        recipeTree = new RecipeManager.RecipeNode("Shopping list", 0, 0, tops);

        Set<String> itemNames = new HashSet<>();
        for (RecipeManager.RecipeNode tree : response.trees) collectNames(tree, itemNames);
        listItemNames = itemNames;
        updateForgingLines();
    }

    /** When the last Forge slot cooking each item (by normalised name) is done, for items still cooking. */
    private static Map<String, Long> cookingEnds() {
        long now = System.currentTimeMillis();
        Map<String, Long> out = new HashMap<>();
        for (ForgeTracker.Entry entry : ForgeTracker.getEntries()) {
            if (entry.endsAt > now) out.merge(ItemNames.normalize(entry.name), entry.endsAt, Math::max);
        }
        return out;
    }

    /**
     * Fills in each step's forge time: every forge craft still to make under it, one after another (own time ×
     * how many are still to make, plus all its ingredients' times). Items cooking now add the time they have
     * left; crafts not started add their full time, cut by Quick Forge and the mayor bonus.
     */
    private static void setForgeTimes(RecipeManager.RecipeNode node, Map<String, Long> cookingEnds, double multiplier) {
        // Item -> finish time, so an item cooking once is counted once even if several steps need it.
        Map<String, Long> cooking = new HashMap<>();
        collectForgeTimes(node, cookingEnds, multiplier, cooking);
    }

    private static long collectForgeTimes(RecipeManager.RecipeNode node, Map<String, Long> cookingEnds, double multiplier,
                                          Map<String, Long> cooking) {
        Map<String, Long> own = new HashMap<>();
        String key = ItemNames.normalize(node.name);
        Long cookingEnd = cookingEnds.get(key);
        if (cookingEnd != null) own.put(key, cookingEnd);
        long ms = 0;
        int toMake = node.amount + node.toCraft;
        if (toMake > 0) {
            ms = Math.round(RecipeManager.getInstance().getForgeSeconds(node.name) * 1000L * multiplier) * toMake;
            if (node.ingredients != null) {
                for (RecipeManager.RecipeNode child : node.ingredients) {
                    ms += collectForgeTimes(child, cookingEnds, multiplier, own);
                }
            }
        }
        node.forgeMs = ms;
        node.forgeCookingEnds = own.values().stream().mapToLong(Long::longValue).toArray();
        cooking.putAll(own);
        return ms;
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
            if (used > left) {
                short_ = true;
                // Counted as held, but some of it is still in the Forge: not usable yet.
                node.cooking = true;
            }
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
        forgingLines = List.copyOf(lines);
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

    /**
     * Position (GUI pixels), size (HUD units, before scaling) and scale (percent, on top of the HUD scale) of one
     * panel.
     */
    public static final class PanelRect {
        public int x;
        public int y;
        public int width;
        public int height;
        /** 0 in configs saved before panels had their own scale; read through {@link #scalePercent()}. */
        public int scale;
        /** GUI-scaled screen size x / y were saved at (0 = not recorded yet); positions follow the screen. */
        public int refWidth;
        public int refHeight;

        public PanelRect(int x, int y, int width, int height, int scale) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.scale = scale;
        }

        public PanelRect(int x, int y, int width, int height) {
            this(x, y, width, height, 100);
        }

        public int scalePercent() {
            return scale > 0 ? clampPanelScale(scale) : 100;
        }
    }

    public static final int MIN_PANEL_SCALE = 25;
    public static final int MAX_PANEL_SCALE = 400;

    public static int clampPanelScale(int percent) {
        return Math.max(MIN_PANEL_SCALE, Math.min(MAX_PANEL_SCALE, percent));
    }

    /** One block of centred/aligned lines under a header (Craftable, Forging). */
    private record Section(String header, List<String> lines, float scale, HudStyle.Align align,
                           int headerColor, int textColor) {}

    private static final int TITLE_BAR = 20;

    /** True while {@link #renderPreview} draws: panels show the sample list below, fully expanded. */
    private boolean previewing = false;
    private static final RecipeManager.RecipeNode SAMPLE_TREE = sampleTree();
    private static final List<String> SAMPLE_CRAFTABLE = List.of("   1× Refined Diamond");
    private static final List<String> SAMPLE_FORGING = List.of("   3× Mithril Plate - 2h 10m");

    /** A small made-up list showing every row state: done, partly gathered, missing and can craft. */
    private static RecipeManager.RecipeNode sampleTree() {
        RecipeManager.RecipeNode total = new RecipeManager.RecipeNode("Total", 512, 1282, new ArrayList<>(List.of(
            new RecipeManager.RecipeNode("Enchanted Diamond", 0, 2, null),
            new RecipeManager.RecipeNode("Mithril", 448, 960, null),
            new RecipeManager.RecipeNode("Gold Ingot", 64, 64, null))));
        RecipeManager.RecipeNode enchanted = new RecipeManager.RecipeNode("Enchanted Mithril", 0, 320, null);
        enchanted.toCraft = 160;
        RecipeManager.RecipeNode recipe = new RecipeManager.RecipeNode("Refined Mithril", 1, 2,
            new ArrayList<>(List.of(enchanted)));
        return new RecipeManager.RecipeNode("Shopping list", 0, 0, new ArrayList<>(List.of(total, recipe)));
    }

    /**
     * Draws the panels {@code style} would show, with sample rows, stacked from (x, y) and scaled to fit
     * {@code width} GUI pixels. Returns the height used. Settings > Appearance uses it to preview unsaved changes.
     * Render thread only.
     */
    public int renderPreview(GuiGraphicsExtractor context, int x, int y, int width, HudStyle style) {
        return HudStyle.withOverride(style, () -> {
            previewing = true;
            try {
                // Keep the outward panel border inside the column.
                int inset = style.panelBorderWidth + 1;
                List<Panel> panels = activePanels();
                // One scale for every panel: as wide as the column allows, but short enough to leave room for the
                // description text under it.
                int widest = 1;
                int totalHeight = 0;
                for (Panel panel : panels) {
                    PanelRect rect = getPanelRect(panel);
                    widest = Math.max(widest, rect.width);
                    totalHeight += measurePanel(panel, rect.width);
                }
                int maxHeight = Math.round(Minecraft.getInstance().getWindow().getGuiScaledHeight() * 0.4f);
                int gaps = panels.size() * (2 * inset + 2);
                float scale = Math.min(1.5f, Math.min((width - 2f * inset) / widest,
                    (maxHeight - gaps) / (float) Math.max(1, totalHeight)));
                int cursor = y + inset;
                for (Panel panel : panels) {
                    PanelRect rect = getPanelRect(panel);
                    int height = renderPanel(context, panel, x + inset, cursor, rect.width, 4000, scale, true);
                    cursor += Math.round(height * scale) + 2 * inset + 2;
                }
                return cursor - y;
            } finally {
                previewing = false;
            }
        });
    }

    private boolean isExpandedForDraw(String nodeKey) {
        return previewing || expandedNodes.getOrDefault(nodeKey, false);
    }

    /**
     * HUD units -> GUI pixels. Window size: 100% is the original size in a 1080p window and grows and shrinks
     * with the window height. GUI Scale: follows Minecraft's GUI Scale. Fixed: same size on screen always.
     */
    public static float scaleFactor() {
        HudStyle style = HudStyle.get();
        float scale = style.hudScale / 100f;
        if (style.sizing == HudStyle.Sizing.GUI_SCALE) return scale;
        Window window = Minecraft.getInstance().getWindow();
        int guiScale = Math.max(1, window.getGuiScale());
        if (style.sizing == HudStyle.Sizing.FIXED) return scale * 2f / guiScale;
        return scale * 2f * (window.getScreenHeight() / 1080f) / guiScale;
    }

    /** HUD units -> GUI pixels for one panel: the HUD scale times that panel's own scale. */
    public float scaleFactor(Panel panel) {
        return scaleFactor() * storedRect(panel).scalePercent() / 100f;
    }

    /**
     * Where a panel goes on the current screen: its saved position moved in proportion to how the screen size
     * changed since it was placed, then kept on screen.
     */
    public PanelRect getPanelRect(Panel panel) {
        PanelRect r = storedRect(panel);
        Window window = Minecraft.getInstance().getWindow();
        int screenW = window.getGuiScaledWidth();
        int screenH = window.getGuiScaledHeight();
        int x = r.refWidth > 0 ? Math.round(r.x * (float) screenW / r.refWidth) : r.x;
        int y = r.refHeight > 0 ? Math.round(r.y * (float) screenH / r.refHeight) : r.y;
        int w = Math.round(r.width * scaleFactor() * r.scalePercent() / 100f);
        // Height is only a limit (panels are often shorter), so just keep the top in view.
        x = Math.max(0, Math.min(x, screenW - Math.min(w, screenW)));
        y = Math.max(0, Math.min(y, screenH - 20));
        PanelRect out = new PanelRect(x, y, r.width, r.height, r.scalePercent());
        out.refWidth = screenW;
        out.refHeight = screenH;
        return out;
    }

    /** The panel as saved, before fitting it to the current screen. */
    private PanelRect storedRect(Panel panel) {
        return switch (panel) {
            case MAIN -> {
                PanelRect r = new PanelRect(widgetX, widgetY, widgetWidth, widgetHeight, widgetScale);
                r.refWidth = widgetRefWidth;
                r.refHeight = widgetRefHeight;
                yield r;
            }
            case CRAFTABLE -> craftablePanel;
            case FORGING -> forgingPanel;
        };
    }

    public synchronized void setPanelRect(Panel panel, int x, int y, int width, int height, int scale) {
        PanelRect rect = new PanelRect(x, y, Math.max(minWidth(panel), width), Math.max(minHeight(panel), height),
            clampPanelScale(scale));
        setRef(rect);
        switch (panel) {
            case MAIN -> {
                widgetX = rect.x;
                widgetY = rect.y;
                widgetWidth = rect.width;
                widgetHeight = rect.height;
                widgetScale = rect.scale;
                widgetRefWidth = rect.refWidth;
                widgetRefHeight = rect.refHeight;
            }
            case CRAFTABLE -> craftablePanel = rect;
            case FORGING -> forgingPanel = rect;
        }
        saveConfiguration();
    }

    /** Records the current screen size as the one {@code rect}'s position was placed at. */
    private static void setRef(PanelRect rect) {
        Window window = Minecraft.getInstance().getWindow();
        rect.refWidth = window.getGuiScaledWidth();
        rect.refHeight = window.getGuiScaledHeight();
    }

    /**
     * Configs from before positions followed the screen: take the screen size the HUD is first drawn at as
     * the one the panels were placed at. Render thread only.
     */
    private void recordMissingRefs() {
        if (widgetRefWidth > 0 && craftablePanel.refWidth > 0 && forgingPanel.refWidth > 0) return;
        synchronized (this) {
            Window window = Minecraft.getInstance().getWindow();
            if (widgetRefWidth <= 0) {
                widgetRefWidth = window.getGuiScaledWidth();
                widgetRefHeight = window.getGuiScaledHeight();
            }
            // New copies rather than changing the saved ones in place (the update thread may be saving them).
            if (craftablePanel.refWidth <= 0) craftablePanel = withCurrentRef(craftablePanel);
            if (forgingPanel.refWidth <= 0) forgingPanel = withCurrentRef(forgingPanel);
            saveConfiguration();
        }
    }

    private static PanelRect withCurrentRef(PanelRect r) {
        PanelRect copy = new PanelRect(r.x, r.y, r.width, r.height, r.scalePercent());
        setRef(copy);
        return copy;
    }

    /** Puts every panel back to its starting place (sizes and scales are kept): own panels go right of the main one. */
    public synchronized void resetPanelPositions() {
        widgetX = 10;
        widgetY = 40;
        int sideX = widgetX + Math.round(widgetWidth * scaleFactor(Panel.MAIN)) + 10;
        craftablePanel = new PanelRect(sideX, widgetY, craftablePanel.width, craftablePanel.height,
            craftablePanel.scalePercent());
        forgingPanel = new PanelRect(sideX, widgetY + Math.round(craftablePanel.height * scaleFactor(Panel.CRAFTABLE)) + 10,
            forgingPanel.width, forgingPanel.height, forgingPanel.scalePercent());
        Window window = Minecraft.getInstance().getWindow();
        widgetRefWidth = window.getGuiScaledWidth();
        widgetRefHeight = window.getGuiScaledHeight();
        setRef(craftablePanel);
        setRef(forgingPanel);
        saveConfiguration();
    }

    public static int minWidth(Panel panel) {
        return panel == Panel.MAIN ? 180 : 80;
    }

    public static int minHeight(Panel panel) {
        return panel == Panel.MAIN ? 120 : 40;
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
        recordMissingRefs();
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
        return renderPanel(context, panel, x, y, width, maxHeight, scaleFactor(panel), preview);
    }

    /** As above, at the given HUD units -> GUI pixels factor (Move HUD previews a scale before it is saved). */
    public int renderPanel(GuiGraphicsExtractor context, Panel panel, int x, int y, int width, int maxHeight, float scale,
                           boolean preview) {
        return renderPanel(context, panel, x, y, width, maxHeight, scale, preview, false);
    }

    /** Full height of a panel in HUD units if nothing were cut off (preview only, so empty panels count). */
    private int measurePanel(Panel panel, int width) {
        return renderPanel(null, panel, 0, 0, width, Integer.MAX_VALUE, 1f, true, true);
    }

    private int renderPanel(GuiGraphicsExtractor context, Panel panel, int x, int y, int width, int maxHeight, float scale,
                            boolean preview, boolean measureOnly) {
        HudStyle style = HudStyle.get();
        RecipeManager.RecipeNode root = previewing ? SAMPLE_TREE : this.recipeTree;
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
        if (measureOnly) return panelHeight;

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
            // At least twice the radius tall, or RoundedBox clamps the curve and the bottom corners change shape.
            int coverHeight = Math.max(9, style.panelRadius * 2);
            RoundedBox.fill(context, 0, panelHeight - coverHeight, width, coverHeight, style.panelRadius, false, true,
                style.panelBackground);
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
            for (String message : previewing ? SAMPLE_CRAFTABLE : messages) {
                if (!message.equals("Craftable -")) craftable.add(message.trim());
            }
            // Already sorted, most first, when the lines are built (updateRecipeData).
            if (panel != Panel.MAIN || !craftable.isEmpty()) {
                sections.add(new Section("Craftable", craftable, style.craftableScale, style.craftableAlign,
                    style.sectionHeader, style.sectionText));
            }
        }
        if (forgingHere) {
            List<String> forging = new ArrayList<>();
            for (String line : previewing ? SAMPLE_FORGING : forgingLines) forging.add(line.trim());
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
    /** Wrapped section lines by (lines, width, font); render thread only. The lines rarely change between frames. */
    private record WrapKey(List<String> lines, int maxWidth, String font) {}
    private static final Map<WrapKey, List<String>> WRAP_CACHE = new HashMap<>();

    private static List<String> wrap(Minecraft client, HudStyle style, Section section, int contentWidth) {
        int maxWidth = Math.max(10, (int) Math.floor(contentWidth / Math.max(0.01f, section.scale())));
        WrapKey key = new WrapKey(List.copyOf(section.lines()), maxWidth, style.font);
        List<String> cached = WRAP_CACHE.get(key);
        if (cached != null) return cached;
        // A handful of sections are on screen at once; drop old entries rather than growing forever.
        if (WRAP_CACHE.size() > 16) WRAP_CACHE.clear();
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
        out = List.copyOf(out);
        WRAP_CACHE.put(key, out);
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
        if (node.ingredients != null && !node.ingredients.isEmpty() && isExpandedForDraw(nodeKey)) {
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
        boolean hasEnough = node.amount <= 0 && node.toCraft <= 0 && !node.cooking;
        String nodeKey = makePathKey(pathKey, node.name);
        boolean isExpanded = isExpandedForDraw(nodeKey);
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
        Component mark = style.showMarks ? style.text(hasEnough || (node.amount <= 0 && !node.cooking) ? "✔ " : "✖ ", statusColor, false) : Component.empty();
        Component amount = style.text(amountText(node) + " ", statusColor, false);
        boolean bold = level == 0 && style.boldRootNames;
        Component name = style.text(node.name, nameColor, bold);
        String forge = forgeText(node, level);
        Component tag = forge.isEmpty() ? Component.empty() : style.text(forge, nameColor, false);
        int markWidth = client.font.width(mark);
        int amountWidth = client.font.width(amount);
        int tagWidth = client.font.width(tag);
        int maxTextWidth = Math.max(10, nodeWidth - iconOffset);
        float textScale;
        if (forge.isEmpty()) {
            int totalTextWidth = markWidth + amountWidth + client.font.width(name);
            textScale = Math.min(rowScale, (float) maxTextWidth / Math.max(1, totalTextWidth));
        } else {
            // The forge time always shows; a long name is cut short to make room instead of shrinking the row.
            textScale = rowScale;
            int room = Math.round(maxTextWidth / rowScale) - markWidth - amountWidth - tagWidth;
            name = fitName(client.font, style, node.name, nameColor, bold, room);
        }
        int nameWidth = client.font.width(name);

        context.pose().pushMatrix();
        context.pose().translate(x + indent + iconOffset, textY(y, nodeHeight, textScale));
        context.pose().scale(textScale, textScale);
        context.text(client.font, mark, 0, 0, 0xFFFFFFFF, style.textShadow);
        context.text(client.font, amount, markWidth, 0, 0xFFFFFFFF, style.textShadow);
        context.text(client.font, name, markWidth + amountWidth, 0, 0xFFFFFFFF, style.textShadow);
        context.text(client.font, tag, markWidth + amountWidth + nameWidth, 0, 0xFFFFFFFF, style.textShadow);
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
    public TotalMode getTotalMode() { return totalMode; }
    public void setTotalMode(TotalMode totalMode) {
        this.totalMode = totalMode;
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
    public boolean isDebugLogging() { return InventoryReader.debugLogging; }
    public void setDebugLogging(boolean debugLogging) {
        InventoryReader.debugLogging = debugLogging;
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
            case REMAINING -> number((long) node.amount + node.toCraft) + "×";
            case REQUIRED -> number(node.required) + "×";
            case HAVE_NEED -> number(Math.max(0L, (long) node.required - node.amount - node.toCraft)) + "/"
                + number(node.required);
        };
    }

    /**
     * Forge time still ahead for a row, per Settings > Appearance > Forge times: "[25hrs]", "[45m]" or
     * "[<1m]"; empty when there is none or the setting hides it for this row (level 0 = the top rows).
     */
    public static String forgeText(RecipeManager.RecipeNode node, int level) {
        HudStyle.ForgeTimes mode = HudStyle.get().forgeTimes;
        if (mode == HudStyle.ForgeTimes.OFF || (mode == HudStyle.ForgeTimes.TOP_LEVEL && level > 0)) return "";
        long left = node.forgeLeft(System.currentTimeMillis());
        if (left <= 0) return "";
        long minutes = left / 60_000;
        if (minutes < 1) return "[<1m]";
        if (minutes < 60) return "[" + minutes + "m]";
        return "[" + (minutes / 60) + "hrs]";
    }

    /**
     * {@code name} followed by a space if it fits in {@code room} font pixels, otherwise cut short and ended
     * with "..." (so a forge time drawn straight after reads "Mithril Dri...[25hrs]").
     */
    static Component fitName(Font font, HudStyle style, String name, int color, boolean bold, int room) {
        return fitName(font, text -> style.text(text, color, bold), name, room);
    }

    /** As above, with {@code make} turning text into a styled component (screens use their own style). */
    static Component fitName(Font font, java.util.function.Function<String, Component> make, String name, int room) {
        Component full = make.apply(name + " ");
        if (font.width(full) <= room) return full;
        // Longest start of the name that still fits with "..." (binary search: runs every frame per row).
        int lo = 0;
        int hi = name.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (font.width(make.apply(name.substring(0, mid).stripTrailing() + "...")) <= room) lo = mid;
            else hi = mid - 1;
        }
        return make.apply(name.substring(0, lo).stripTrailing() + "...");
    }

    /** An amount as the style wants it: "512", "5,120", or with Short numbers on "5.1k", "500m", "1.5b". */
    public static String number(long value) {
        if (!HudStyle.get().shortNumbers) return String.format(Locale.ROOT, "%,d", value);
        return shortNumber(value);
    }

    /** 999 -> "999", 5120 -> "5.1k", 512000 -> "512k", 500000000 -> "500m", 1500000000 -> "1.5b". */
    static String shortNumber(long value) {
        long abs = Math.abs(value);
        if (abs < 1000) return Long.toString(value);
        String[] units = {"k", "m", "b", "t"};
        double scaled = value;
        int unit = -1;
        while (Math.abs(scaled) >= 1000 && unit < units.length - 1) {
            scaled /= 1000;
            unit++;
        }
        // One decimal below 100 (5.1k, 12.5m), none above (512k); always rounded towards zero.
        String text = Math.abs(scaled) < 100 ? String.format(Locale.ROOT, "%.1f", (long) (scaled * 10) / 10.0)
            : Long.toString((long) scaled);
        if (text.endsWith(".0")) text = text.substring(0, text.length() - 2);
        return text + units[unit];
    }

    /**
     * Green when held; blue when held only by counting copies still cooking in the Forge; yellow when the rest
     * can be crafted from materials you have; with remaining mode on,
     * orange when partly gathered and red when none yet.
     */
    public static int progressColor(RecipeManager.RecipeNode node, boolean showRemaining) {
        HudStyle style = HudStyle.get();
        if (node.amount <= 0) return node.toCraft > 0 ? style.craftable : node.cooking ? style.cooking : style.done;
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

