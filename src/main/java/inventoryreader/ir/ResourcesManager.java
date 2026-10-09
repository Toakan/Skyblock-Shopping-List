package inventoryreader.ir;

import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns resources.json: the player's item counts by display name. Counts live in memory and are written
 * to disk on a background thread, so callers on the render thread never touch the file system.
 * Every item seen is tracked; names seeded from recipes also appear with a zero count.
 */
public class ResourcesManager {
    private static final ResourcesManager INSTANCE = new ResourcesManager();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Integer>>() {}.getType();

    private final AtomicLong version = new AtomicLong();
    private Map<String, Integer> resources;
    /** The file {@link #resources} was read from: changes are only ever saved back there (see profiles). */
    private File loadedFrom;
    private Map<String, String> keyByNormalized;

    private ResourcesManager() {}

    public static ResourcesManager getInstance() {
        return INSTANCE;
    }

    /** Incremented on every change; lets the HUD skip recomputing when nothing moved. */
    public long getVersion() {
        return version.get();
    }

    /** Drops the in-memory copy and re-reads resources.json (after a reset or a profile switch). */
    public synchronized void reload() {
        resources = null;
        loaded();
        version.incrementAndGet();
    }

    private Map<String, Integer> loaded() {
        if (resources == null) {
            loadedFrom = FilePathManager.resourcesJson();
            Map<String, Integer> fromFile = JsonFiles.read(loadedFrom, MAP_TYPE);
            resources = new LinkedHashMap<>();
            if (fromFile != null) {
                fromFile.forEach((k, v) -> {
                    // Re-clean saved names so entries written before a cleaning rule existed merge with new ones.
                    if (!ItemNames.isJunk(k)) resources.merge(ItemNames.clean(k), v == null ? 0 : v, Integer::sum);
                });
            }
            rebuildIndex();
        }
        return resources;
    }

    private void rebuildIndex() {
        keyByNormalized = new HashMap<>();
        for (String key : resources.keySet()) {
            String norm = ItemNames.normalize(key);
            String existing = keyByNormalized.get(norm);
            // Prefer the symbol-prefixed spelling, which is what the recipes use.
            if (existing == null || ItemNames.clean(key).length() > ItemNames.clean(existing).length()) {
                keyByNormalized.put(norm, key);
            }
        }
    }

    private void changed() {
        version.incrementAndGet();
        // This map and the file it came from go together, so after a profile switch a late save of the old
        // profile's counts still lands in the old profile's file. The copy is made on the writer thread.
        Map<String, Integer> map = resources;
        JsonFiles.writeAsync(loadedFrom, () -> {
            synchronized (this) {
                return new LinkedHashMap<>(map);
            }
        });
    }

    /** Adds the names as zero-count entries, and drops plain names that duplicate a symbol-prefixed one. */
    public synchronized void ensureResourceNames(Collection<String> names) {
        Map<String, Integer> res = loaded();
        boolean dirty = false;
        for (String n : names) {
            if (ItemNames.isJunk(n)) continue;
            String name = ItemNames.clean(n);
            if (!res.containsKey(name)) {
                res.put(name, 0);
                dirty = true;
            }
        }
        rebuildIndex();
        // Merge duplicates ("Fine Jade Gemstone" vs "☘ Fine Jade Gemstone") into the preferred key.
        for (String key : new ArrayList<>(res.keySet())) {
            String preferred = keyByNormalized.get(ItemNames.normalize(key));
            if (preferred != null && !preferred.equals(key)) {
                res.merge(preferred, res.remove(key), Integer::sum);
                dirty = true;
            }
        }
        if (dirty) changed();
    }

    /** Resolves any spelling of an item name to its tracked key, or null if the item is not tracked. */
    private String resolve(String name) {
        loaded();
        String cleaned = ItemNames.clean(name);
        if (resources.containsKey(cleaned)) return cleaned;
        return keyByNormalized.get(ItemNames.normalize(cleaned));
    }

    /** Applies count changes (item name to delta). */
    public synchronized void saveData(Map<String, Integer> deltas) {
        if (deltas == null || deltas.isEmpty()) return;
        boolean dirty = false;
        for (Map.Entry<String, Integer> e : deltas.entrySet()) {
            if (e.getValue() == null || e.getValue() == 0) continue;
            String key = resolve(e.getKey());
            if (key == null) {
                // Not seen before: start tracking it. Dropping it would lose the count for good, because
                // the inventory/container/sack snapshots already record it as counted.
                if (ItemNames.isJunk(e.getKey())) continue;
                key = ItemNames.clean(e.getKey());
                resources.put(key, 0);
                keyByNormalized.putIfAbsent(ItemNames.normalize(key), key);
            }
            resources.merge(key, e.getValue(), Integer::sum);
            dirty = true;
        }
        if (dirty) changed();
    }

    public synchronized Map<String, Integer> getAllResources() {
        return new LinkedHashMap<>(loaded());
    }

    public synchronized int getResourceByName(String name) {
        String key = resolve(name);
        return key == null ? 0 : resources.getOrDefault(key, 0);
    }

    public synchronized void setResourceAmount(String name, int amount) {
        String key = resolve(name);
        loaded().put(key != null ? key : ItemNames.clean(name), amount);
        if (key == null) rebuildIndex();
        changed();
    }

    public List<ResourceEntry> getAllResourceEntries() {
        List<ResourceEntry> list = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : getAllResources().entrySet()) {
            if (entry.getValue() > 0) {
                list.add(new ResourceEntry(entry.getKey(), entry.getValue()));
            }
        }
        return list;
    }

    public List<ResourceEntry> getAllResourceEntriesIncludingZero() {
        List<ResourceEntry> list = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : getAllResources().entrySet()) {
            list.add(new ResourceEntry(entry.getKey(), entry.getValue()));
        }
        list.sort((a, b) -> a.name.compareTo(b.name));
        return list;
    }

    /** Shopping list for a single recipe. */
    public RemainingResponse getRemainingIngredients(String name, int amt) {
        ShoppingResponse response = getShoppingList(List.of(new ShoppingListEntry(name, amt, 0, false)));
        return new RemainingResponse(name, response.trees.get(0), response.craftable);
    }

    /**
     * Works out what is still missing for every entry together. Stock is shared: entries are handled in list
     * order and each one only gets what earlier entries left over, so nothing is counted twice.
     */
    public ShoppingResponse getShoppingList(List<ShoppingListEntry> entries) {
        Map<String, Map<String, Integer>> forging = RecipeManager.getInstance().getAllRecipes();
        // One snapshot for the whole calculation, so a change mid-way can't make "owned" and "available" disagree.
        Map<String, Integer> snapshot = getAllResources();
        Map<String, Integer> highestPossibleResources = new LinkedHashMap<>(snapshot);
        Map<String, Integer> currentAvailableResources = new LinkedHashMap<>(highestPossibleResources);
        Map<String, Integer> messages = new LinkedHashMap<>();

        java.util.Set<String> visited = new java.util.HashSet<>();
        for (ShoppingListEntry entry : entries) {
            initializeResourceMaps(entry.recipe, forging, highestPossibleResources, currentAvailableResources, visited);
        }

        // Phase 1: craft what can be crafted, entry by entry, and note how many of each are still to make.
        int[] toCraft = new int[entries.size()];
        int[] fromStock = new int[entries.size()];
        // How many of each entry can be crafted right now from what is held (shown as "can craft", not as held).
        int[] craftedNow = new int[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            ShoppingListEntry entry = entries.get(i);
            int need = entry.amount;
            if (entry.isHaveTotal()) {
                // Have total: held copies of the item itself count towards the amount and are kept from later
                // entries. Add more: the amount is how many more to make, so held copies don't count.
                int held = Math.max(0, currentAvailableResources.getOrDefault(entry.recipe, 0));
                fromStock[i] = Math.min(held, need);
                need -= fromStock[i];
                currentAvailableResources.put(entry.recipe, held - fromStock[i]);
                highestPossibleResources.merge(entry.recipe, -fromStock[i], Integer::sum);
            }
            int old = highestPossibleResources.getOrDefault(entry.recipe, 0);
            if (need > 0) {
                buildRecipe(entry.recipe, need, forging, highestPossibleResources, currentAvailableResources, messages, 0);
            }
            int crafted = highestPossibleResources.getOrDefault(entry.recipe, 0) - old;
            craftedNow[i] = Math.max(0, Math.min(need, crafted));
            toCraft[i] = Math.max(0, need - crafted);
        }

        // Phase 2: expand what is still missing. A root does not draw on existing stock of itself: the
        // player asked for this many more. Phase 1 counted items craftable from materials as stock; owned
        // tells them apart from items actually held.
        Map<String, Integer> owned = new LinkedHashMap<>(snapshot);
        for (int i = 0; i < entries.size(); i++) {
            if (fromStock[i] > 0) owned.merge(entries.get(i).recipe, -fromStock[i], Integer::sum);
        }
        List<RecipeManager.RecipeNode> trees = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            ShoppingListEntry entry = entries.get(i);
            List<RecipeManager.RecipeNode> ingredients = new ArrayList<>();
            Map<String, Integer> recipe = forging.get(entry.recipe);
            if (recipe != null) {
                // Expand both what is missing and what can be crafted now, so the steps still to do show.
                giveBack(recipe, craftedNow[i], highestPossibleResources);
                for (Map.Entry<String, Integer> ingredient : recipe.entrySet()) {
                    ingredients.add(expandRequiredRecipe(ingredient.getKey(), ingredient.getValue() * (toCraft[i] + craftedNow[i]),
                        forging, highestPossibleResources, owned, 1));
                }
            }
            RecipeManager.RecipeNode root = new RecipeManager.RecipeNode(entry.recipe, toCraft[i], entry.amount, ingredients);
            root.toCraft = craftedNow[i];
            trees.add(root);
        }
        return new ShoppingResponse(trees, totalOf(trees, forging), ingredientTotalOf(trees), messages);
    }

    /** Raw materials (leaves) across all trees, summed; missing items first, most missing at the top. */
    private static RecipeManager.RecipeNode totalOf(List<RecipeManager.RecipeNode> trees,
                                                    Map<String, Map<String, Integer>> forging) {
        Map<String, int[]> sums = new LinkedHashMap<>();
        for (RecipeManager.RecipeNode tree : trees) {
            if (tree.ingredients != null) {
                for (RecipeManager.RecipeNode child : tree.ingredients) collectLeaves(child, sums);
            }
        }
        List<RecipeManager.RecipeNode> leaves = new ArrayList<>();
        int missing = 0;
        int required = 0;
        for (Map.Entry<String, int[]> e : sums.entrySet()) {
            int[] v = e.getValue();
            // Held intermediates are leaves of the tree too, but the Total only lists raw materials.
            if (v[1] <= 0 || forging.containsKey(e.getKey())) continue;
            leaves.add(new RecipeManager.RecipeNode(e.getKey(), v[0], v[1], Collections.emptyList()));
            missing += v[0];
            required += v[1];
        }
        sortTotalRows(leaves);
        return new RecipeManager.RecipeNode("Total", missing, required, leaves);
    }

    /** Direct ingredients of every entry, summed by name; intermediates such as Refined Titanium included. */
    private static RecipeManager.RecipeNode ingredientTotalOf(List<RecipeManager.RecipeNode> trees) {
        Map<String, int[]> sums = new LinkedHashMap<>();
        for (RecipeManager.RecipeNode tree : trees) {
            if (tree.ingredients == null) continue;
            for (RecipeManager.RecipeNode child : tree.ingredients) {
                int[] v = sums.computeIfAbsent(child.name, k -> new int[3]);
                v[0] += Math.max(0, child.amount);
                v[1] += child.required;
                v[2] += child.toCraft;
            }
        }
        List<RecipeManager.RecipeNode> rows = new ArrayList<>();
        int missing = 0;
        int required = 0;
        int toCraft = 0;
        for (Map.Entry<String, int[]> e : sums.entrySet()) {
            int[] v = e.getValue();
            if (v[1] <= 0) continue;
            RecipeManager.RecipeNode row = new RecipeManager.RecipeNode(e.getKey(), v[0], v[1], Collections.emptyList());
            row.toCraft = v[2];
            rows.add(row);
            missing += v[0];
            required += v[1];
            toCraft += v[2];
        }
        sortTotalRows(rows);
        RecipeManager.RecipeNode total = new RecipeManager.RecipeNode("Total", missing, required, rows);
        total.toCraft = toCraft;
        return total;
    }

    /** Rows still to get or craft first, most at the top; finished rows last. */
    private static void sortTotalRows(List<RecipeManager.RecipeNode> rows) {
        rows.sort((a, b) -> {
            int aLeft = a.amount + a.toCraft;
            int bLeft = b.amount + b.toCraft;
            boolean aDone = aLeft <= 0;
            boolean bDone = bLeft <= 0;
            if (aDone != bDone) return aDone ? 1 : -1;
            return Integer.compare(bLeft, aLeft);
        });
    }

    private static void collectLeaves(RecipeManager.RecipeNode node, Map<String, int[]> sums) {
        if (node.ingredients == null || node.ingredients.isEmpty()) {
            int[] v = sums.computeIfAbsent(node.name, k -> new int[2]);
            v[0] += Math.max(0, node.amount);
            v[1] += node.required;
            return;
        }
        for (RecipeManager.RecipeNode child : node.ingredients) collectLeaves(child, sums);
    }

    private void buildRecipe(String currentItem, int multiplier, Map<String, Map<String, Integer>> forging,
                            Map<String, Integer> highestPossibleResources, Map<String, Integer> currentAvailableResources,
                            Map<String, Integer> messages, int depth) {
        Map<String, Integer> recipe = forging.get(currentItem);
        if (recipe == null || depth > RecipeManager.MAX_DEPTH) return;
        Map<String, Integer> madeResources = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : recipe.entrySet()) {
            String item = entry.getKey();
            int quantity = entry.getValue();
            if (forging.containsKey(item)) {
                int need = Math.max(0, (quantity * multiplier) - currentAvailableResources.getOrDefault(item, 0));
                if (need > 0) {
                    buildRecipe(item, need, forging, highestPossibleResources, currentAvailableResources, messages, depth + 1);
                    madeResources.put(item, currentAvailableResources.getOrDefault(item, 0));
                    currentAvailableResources.put(item, 0);
                } else {
                    madeResources.put(item, quantity * multiplier);
                    currentAvailableResources.put(item, currentAvailableResources.getOrDefault(item, 0) - quantity * multiplier);
                }
            }
        }
        for (Map.Entry<String, Integer> entry : madeResources.entrySet()) {
            String item = entry.getKey();
            int quantity = entry.getValue();
            if (quantity > 0) {
                currentAvailableResources.put(item,
                        currentAvailableResources.getOrDefault(item, 0) + quantity);
            }
        }
        check(currentItem, multiplier, forging, highestPossibleResources, currentAvailableResources, messages);
    }

    private void check(String currentItem, int multiplier, Map<String, Map<String, Integer>> forging,
                       Map<String, Integer> highestPossibleResources, Map<String, Integer> currentAvailableResources,
                       Map<String, Integer> messages) {
        Map<String, Integer> recipe = forging.get(currentItem);
        if (recipe == null) return;

        List<Integer> count = new ArrayList<>();
        Map<String, Integer> possibleItemsDict = new LinkedHashMap<>();

        for (Map.Entry<String, Integer> entry : recipe.entrySet()) {
            String baseItem = entry.getKey();
            int quantityOfBaseItem = Math.max(1, entry.getValue());
            int possibleItems = currentAvailableResources.getOrDefault(baseItem, 0) / quantityOfBaseItem;
            possibleItemsDict.put(baseItem, possibleItems);
            count.add(multiplier - possibleItems);
        }

        int maxcount = count.stream().mapToInt(i -> i).max().orElse(0);
        maxcount = Math.max(maxcount, 0); // If maxcount <= 0, we have enough resources

        int amountAbleToCraft = multiplier - maxcount;

        highestPossibleResources.put(currentItem,
                                    highestPossibleResources.getOrDefault(currentItem, 0) + amountAbleToCraft);
        currentAvailableResources.put(currentItem,
                                     currentAvailableResources.getOrDefault(currentItem, 0) + amountAbleToCraft);

        if (amountAbleToCraft > 0) {
            messages.put(currentItem,
                          messages.getOrDefault(currentItem, 0) + amountAbleToCraft);
        }

        allocate(currentItem, multiplier, maxcount, possibleItemsDict, forging,
                highestPossibleResources, currentAvailableResources);
    }

    private void allocate(String currentItem, int multiplier, int maxcount, Map<String, Integer> possibleItemsDict,
                          Map<String, Map<String, Integer>> forging, Map<String, Integer> highestPossibleResources,
                          Map<String, Integer> currentAvailableResources) {
        Map<String, Integer> recipe = forging.get(currentItem);
        if (recipe == null) return;

        int amountAbleToCraftOfHigherMaterial = multiplier - maxcount;

        for (Map.Entry<String, Integer> entry : recipe.entrySet()) {
            String baseItem = entry.getKey();
            int quantityOfBaseItem = entry.getValue();
            highestPossibleResources.put(baseItem,
                   highestPossibleResources.getOrDefault(baseItem, 0) -
                   quantityOfBaseItem * amountAbleToCraftOfHigherMaterial);
        }

        if (multiplier != 0) {
            for (Map.Entry<String, Integer> entry : recipe.entrySet()) {
                String baseItem = entry.getKey();
                int quantityOfBaseItem = entry.getValue();
                int possibleItems = possibleItemsDict.getOrDefault(baseItem, 0);
                int amountLeftToAllocate = Math.min(multiplier, possibleItems);
                currentAvailableResources.put(baseItem,
                       currentAvailableResources.getOrDefault(baseItem, 0) -
                       quantityOfBaseItem * amountLeftToAllocate);
            }
        }
    }

    /**
     * Phase 1 used up the ingredients of everything it crafted. Before expanding {@code crafted} of an item into
     * its ingredients, those are put back so the ingredient rows can draw on them again.
     */
    private static void giveBack(Map<String, Integer> recipe, int crafted, Map<String, Integer> highestPossibleResources) {
        if (crafted <= 0) return;
        for (Map.Entry<String, Integer> ingredient : recipe.entrySet()) {
            highestPossibleResources.merge(ingredient.getKey(), ingredient.getValue() * crafted, Integer::sum);
        }
    }

    /**
     * Builds the shopping-list node for {@code needed} of an item: stock the player has is used first,
     * {@code amount} is what is still missing. Stock beyond what is in {@code owned} is craftable from held
     * materials and goes into {@code toCraft}. Both the missing part and the part to craft are expanded into
     * ingredients, so every crafting step still to do shows; held items are not expanded.
     */
    private RecipeManager.RecipeNode expandRequiredRecipe(String currentName, int needed, Map<String, Map<String, Integer>> forging,
                                                          Map<String, Integer> highestPossibleResources, Map<String, Integer> owned,
                                                          int depth) {
        int have = highestPossibleResources.getOrDefault(currentName, 0);
        int fromStock = Math.max(0, Math.min(have, needed));
        highestPossibleResources.put(currentName, have - fromStock);
        int missing = needed - fromStock;
        int held = owned.getOrDefault(currentName, 0);
        int fromHeld = Math.max(0, Math.min(held, fromStock));
        owned.put(currentName, held - fromHeld);

        Map<String, Integer> recipe = forging.get(currentName);
        RecipeManager.RecipeNode node;
        // Held items (nothing missing, nothing to craft) have no steps left, so they are not expanded.
        if (recipe == null || depth > RecipeManager.MAX_DEPTH || missing + fromStock - fromHeld <= 0) {
            node = new RecipeManager.RecipeNode(currentName, missing, needed, Collections.emptyList());
        } else {
            List<RecipeManager.RecipeNode> ingredients = new ArrayList<>();
            int toCraft = fromStock - fromHeld;
            giveBack(recipe, toCraft, highestPossibleResources);
            for (Map.Entry<String, Integer> entry : recipe.entrySet()) {
                ingredients.add(expandRequiredRecipe(entry.getKey(), entry.getValue() * (missing + toCraft), forging,
                    highestPossibleResources, owned, depth + 1));
            }
            node = new RecipeManager.RecipeNode(currentName, missing, needed, ingredients);
        }
        node.toCraft = fromStock - fromHeld;
        return node;
    }

    private void initializeResourceMaps(String targetItem, Map<String, Map<String, Integer>> forging,
                                       Map<String, Integer> highestPossibleResources,
                                       Map<String, Integer> currentAvailableResources,
                                       java.util.Set<String> visited) {
        if (!visited.add(targetItem)) {
            return; // already visited — break the cycle
        }
        highestPossibleResources.putIfAbsent(targetItem, 0);
        currentAvailableResources.putIfAbsent(targetItem, 0);

        Map<String, Integer> recipe = forging.get(targetItem);
        if (recipe != null) {
            for (String ingredient : recipe.keySet()) {
                highestPossibleResources.putIfAbsent(ingredient, 0);
                currentAvailableResources.putIfAbsent(ingredient, 0);
                initializeResourceMaps(ingredient, forging, highestPossibleResources, currentAvailableResources, visited);
            }
        }
    }

    public static class ResourceEntry {
        public String name;
        public int amount;
        public ResourceEntry(String name, int amount) {
            this.name = name;
            this.amount = amount;
        }
    }

    public static class ShoppingResponse {
        /** One tree per list entry, in list order. */
        public final List<RecipeManager.RecipeNode> trees;
        /** Raw materials across all entries; children are the individual items. */
        public final RecipeManager.RecipeNode total;
        /** Direct ingredients of every entry, summed; the Total when set to Recipe ingredients. */
        public final RecipeManager.RecipeNode ingredientTotal;
        /** What can be crafted right now from current stock, item name to count. */
        public final Map<String, Integer> craftable;

        public ShoppingResponse(List<RecipeManager.RecipeNode> trees, RecipeManager.RecipeNode total,
                                RecipeManager.RecipeNode ingredientTotal, Map<String, Integer> craftable) {
            this.trees = trees;
            this.total = total;
            this.ingredientTotal = ingredientTotal;
            this.craftable = craftable;
        }
    }

    public static class RemainingResponse {
        public String name;
        public RecipeManager.RecipeNode full_recipe;
        public Map<String, Integer> messages;

        public RemainingResponse(String name, RecipeManager.RecipeNode fullRecipe, Map<String, Integer> messages) {
            this.name = name;
            this.full_recipe = fullRecipe;
            this.messages = messages;
        }
    }
}
