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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns resources.json: the player's item counts by display name. Counts live in memory and are written
 * to disk on a background thread, so callers on the render thread never touch the file system.
 * Every item seen is tracked; names seeded from recipes also appear with a zero count.
 */
public class ResourcesManager {
    private static final ResourcesManager INSTANCE = new ResourcesManager();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Integer>>() {}.getType();
    /** Recipe trees are acyclic after sanitising; this only stops pathological data from overflowing the stack. */
    private static final int MAX_DEPTH = 64;

    private final File file = FilePathManager.RESOURCES_JSON;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "IR-ResourcesWriter");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong version = new AtomicLong();
    private Map<String, Integer> resources;
    private Map<String, String> keyByNormalized;

    private ResourcesManager() {}

    public static ResourcesManager getInstance() {
        return INSTANCE;
    }

    /** Incremented on every change; lets the HUD skip recomputing when nothing moved. */
    public long getVersion() {
        return version.get();
    }

    /** Drops the in-memory copy and re-reads resources.json (after a reset). */
    public synchronized void reload() {
        resources = null;
        loaded();
        version.incrementAndGet();
    }

    private Map<String, Integer> loaded() {
        if (resources == null) {
            Map<String, Integer> fromFile = JsonFiles.read(file, MAP_TYPE);
            resources = new LinkedHashMap<>();
            if (fromFile != null) {
                fromFile.forEach((k, v) -> {
                    if (!ItemNames.isJunk(k)) resources.put(k, v == null ? 0 : v);
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
        Map<String, Integer> snapshot = new LinkedHashMap<>(resources);
        writer.execute(() -> JsonFiles.write(file, snapshot));
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

    public RemainingResponse getRemainingIngredients(String name, int amt) {
        Map<String, Map<String, Integer>> forging = RecipeManager.getInstance().getAllRecipes();
        Map<String, Integer> highestPossibleResources = getAllResources();
        Map<String, Integer> currentAvailableResources = new LinkedHashMap<>(highestPossibleResources);
        Map<String, Integer> messages = new LinkedHashMap<>();

        initializeResourceMaps(name, forging, highestPossibleResources, currentAvailableResources, new java.util.HashSet<>());

        int old = highestPossibleResources.getOrDefault(name, 0);
        buildRecipe(name, amt, forging, highestPossibleResources, currentAvailableResources, messages, 0);
        int updated = highestPossibleResources.getOrDefault(name, 0);

        RecipeManager.RecipeNode fullRecipe;
        if (updated - old >= amt) {
            fullRecipe = expandRequiredRecipe(name, (updated - old) - amt, forging, highestPossibleResources, 0);
        } else {
            fullRecipe = expandRequiredRecipe(name, amt - (updated - old), forging, highestPossibleResources, 0);
        }
        return new RemainingResponse(name, fullRecipe, messages);
    }

    private void buildRecipe(String currentItem, int multiplier, Map<String, Map<String, Integer>> forging,
                            Map<String, Integer> highestPossibleResources, Map<String, Integer> currentAvailableResources,
                            Map<String, Integer> messages, int depth) {
        Map<String, Integer> recipe = forging.get(currentItem);
        if (recipe == null || depth > MAX_DEPTH) return;
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

    private RecipeManager.RecipeNode expandRequiredRecipe(String currentName, int multiplier, Map<String, Map<String, Integer>> forging,
                                                          Map<String, Integer> highestPossibleResources, int depth) {
        if (!forging.containsKey(currentName) || depth > MAX_DEPTH) {
            int have = highestPossibleResources.getOrDefault(currentName, 0);
            if (have < multiplier) {
                highestPossibleResources.put(currentName, 0);
                return new RecipeManager.RecipeNode(currentName, multiplier - have, Collections.emptyList());
            } else {
                highestPossibleResources.put(currentName, have - multiplier);
                return new RecipeManager.RecipeNode(currentName, 0, Collections.emptyList());
            }
        }
        List<RecipeManager.RecipeNode> ingredients = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : forging.get(currentName).entrySet()) {
            String item = entry.getKey();
            int required = entry.getValue() * multiplier;
            int have = highestPossibleResources.getOrDefault(item, 0);
            if (have < required) {
                // Intermediates only need the shortfall crafted; raw materials report the full requirement.
                int toExpand = forging.containsKey(item) ? required - have : required;
                ingredients.add(expandRequiredRecipe(item, toExpand, forging, highestPossibleResources, depth + 1));
                if (forging.containsKey(item)) highestPossibleResources.put(item, 0);
            } else {
                highestPossibleResources.put(item, have - required);
                ingredients.add(expandRequiredRecipe(item, 0, forging, highestPossibleResources, depth + 1));
            }
        }
        return new RecipeManager.RecipeNode(currentName, multiplier, ingredients);
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
