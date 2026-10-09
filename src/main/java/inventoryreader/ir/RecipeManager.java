package inventoryreader.ir;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public class RecipeManager {
    private static final RecipeManager INSTANCE = new RecipeManager();
    private volatile Map<String, Map<String, Integer>> recipes = Collections.emptyMap();
    private volatile List<String> recipeNames = Collections.emptyList();
    private volatile Set<String> itemNames = Collections.emptySet();
    /** Base forge time in seconds per forge item (before Quick Forge or mayor bonuses). */
    private volatile Map<String, Integer> forgeSeconds = Collections.emptyMap();

    /** Recipe trees are acyclic after sanitising; this only stops pathological data from overflowing the stack. */
    static final int MAX_DEPTH = 64;

    private RecipeManager() {}

    public static RecipeManager getInstance() {
        return INSTANCE;
    }

    private void loadRecipes() {
        Gson gson = new Gson();
        try {
            Map<String, Map<String, Integer>> working = new LinkedHashMap<>();

            Map<String, Map<String, Integer>> forging = readRecipeMap(gson, FilePathManager.FORGING_JSON);
            Map<String, Map<String, Integer>> remote   = readRecipeMap(gson, FilePathManager.REMOTE_RECIPES_JSON);
            Map<String, Map<String, Integer>> remoteForge = readRecipeMap(gson, FilePathManager.REMOTE_FORGE_JSON);
            Map<String, Map<String, Integer>> remoteShop = readRecipeMap(gson, FilePathManager.REMOTE_SHOP_JSON);

            // Only use the hardcoded gemstone fallback when we have no remote data yet;
            // once the NEU fetch has produced remote recipes, those contain the gemstone
            // recipes with correct display names — loading both causes symbol-prefix
            // mismatches that create duplicate entries in the recipe list.
            boolean hasRemote = remote != null && !remote.isEmpty();
            Map<String, Map<String, Integer>> gemstone = hasRemote
                    ? null
                    : readRecipeMap(gson, FilePathManager.GEMSTONE_RECIPES_JSON);

            if (forging != null)     working.putAll(forging);
            if (gemstone != null)    working.putAll(gemstone);
            if (remote != null)      working.putAll(remote);
            if (remoteForge != null) working.putAll(remoteForge);
            // Shop purchases never replace a crafting or forge recipe.
            if (remoteShop != null) remoteShop.forEach(working::putIfAbsent);

            Map<String, Map<String, Integer>> sanitized = sanitizeRecipes(working);

            List<String> newNames = new ArrayList<>(sanitized.keySet());

            Set<String> allNames = new LinkedHashSet<>(sanitized.keySet());
            for (Map<String, Integer> m : sanitized.values()) allNames.addAll(m.keySet());
            itemNames = Collections.unmodifiableSet(allNames);

            recipes = Collections.unmodifiableMap(sanitized);
            recipeNames = Collections.unmodifiableList(newNames);
            Map<String, Integer> times = JsonFiles.read(FilePathManager.FORGE_TIMES_JSON,
                new com.google.gson.reflect.TypeToken<Map<String, Integer>>(){}.getType());
            forgeSeconds = times == null ? Collections.emptyMap() : Collections.unmodifiableMap(new HashMap<>(times));
        } catch (IOException | JsonParseException e) {
            InventoryReader.LOGGER.error("Failed to load recipes", e);
        }
    }

    /** Re-reads all recipe files. Called at startup and by RemoteRecipeFetcher after a successful fetch. */
    public synchronized void reload() {
        loadRecipes();
        // New recipes can bring new item names; add them to the current profile (once a profile is known).
        FilePathManager.seedResources();
    }

    /** Every item name in the recipes, as outputs or ingredients. */
    public Set<String> getItemNames() {
        return itemNames;
    }

    private Map<String, Map<String, Integer>> readRecipeMap(Gson gson, File file) throws IOException {
        if (file == null || !file.exists() || file.length() == 0) return null;
        try (Reader fr = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(fr);
            if (parsed == null || parsed.isJsonNull()) return null;
            JsonObject root = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
            JsonObject recipesNode;
            if (root != null && root.has("recipes") && root.get("recipes").isJsonObject()) {
                recipesNode = root.getAsJsonObject("recipes");
            } else if (parsed.isJsonObject()) {
                recipesNode = parsed.getAsJsonObject();
            } else {
                return null;
            }

            java.lang.reflect.Type t = new com.google.gson.reflect.TypeToken<Map<String, Map<String, Integer>>>(){}.getType();
            return gson.fromJson(recipesNode, t);
        }
    }

    /** Base forge time of one craft in seconds, or 0 when the item is not made in the Forge. */
    public int getForgeSeconds(String name) {
        Integer seconds = forgeSeconds.get(name);
        return seconds == null ? 0 : seconds;
    }

    public List<String> getRecipeNames() {
        List<String> list = new ArrayList<>(recipeNames);
        list.sort(String::compareToIgnoreCase);
        return list;
    }

    public Map<String, Integer> getSimpleRecipe(String name, int amt) {
        Map<String, Integer> base = recipes.getOrDefault(name, Collections.emptyMap());
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : base.entrySet()) {
            result.put(entry.getKey(), entry.getValue() * amt);
        }
        return result;
    }

    public RecipeNode expandRecipe(String currentName, int multiplier) {
        return expandRecipe(currentName, multiplier, 0);
    }

    private RecipeNode expandRecipe(String currentName, int multiplier, int depth) {
        Map<String, Integer> recipe = recipes.get(currentName);
        if (recipe == null || depth > MAX_DEPTH) {
            return new RecipeNode(currentName, multiplier, Collections.emptyList());
        }
        List<RecipeNode> ingredients = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : recipe.entrySet()) {
            ingredients.add(expandRecipe(entry.getKey(), entry.getValue() * multiplier, depth + 1));
        }
        return new RecipeNode(currentName, multiplier, ingredients);
    }

    public Map<String, Map<String, Integer>> getAllRecipes() {
        return new LinkedHashMap<>(recipes);
    }


    private Map<String, Map<String, Integer>> sanitizeRecipes(Map<String, Map<String, Integer>> input) {
        if (input == null || input.isEmpty()) return Collections.emptyMap();

        // Pass 1: drop entire entries that are known decompression recipes ---
        // These items had recipes of the form "X: {Block of X: 1}" which created
        // bidirectional A↔B cycles with the compression recipe "Block of X: {X: 9}".
        // Dropping these entries removes the cycle while preserving the useful direction.
        Set<String> DECOMPRESSION_SKIP = new HashSet<>(Arrays.asList(
            "Iron Ingot",    // Iron Ingot -> Block of Iron  (cycle with Block of Iron -> Iron Ingot x9)
            "Emerald",       // Emerald -> Block of Emerald  (cycle with Block of Emerald -> Emerald x9)
            "Slimeball",     // Slimeball -> Slime Block     (cycle with Slime Block -> Slimeball x9)
            "Coal",          // Coal -> Block of Coal        (cycle with Block of Coal -> Coal x9)
            "Diamond",       // Diamond -> Block of Diamond  (cycle with Block of Diamond -> Diamond x9)
            "Lapis Lazuli",  // Lapis Lazuli -> Lapis Lazuli Block (cycle with reverse)
            "Wheat",         // Wheat -> Hay Bale            (cycle with Hay Bale -> Wheat x9)
            "Redstone Dust", // Redstone Dust -> Block of Redstone (cycle with reverse)
            "Gold Ingot"     // Gold Ingot -> Block of Gold  (cycle with Block of Gold -> Gold Ingot x9)
        ));

        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : input.entrySet()) {
            String output = entry.getKey();
            Map<String, Integer> ing = entry.getValue();
            if (ing == null || ing.isEmpty()) { out.put(output, ing); continue; }

            // Drop known decompression entries entirely
            if (DECOMPRESSION_SKIP.contains(output)) continue;

            Map<String, Integer> cleaned = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> ie : ing.entrySet()) {
                String name = ie.getKey();
                // Empty or digits-only names are junk.
                if (ItemNames.isJunk(name)) continue;
                // --- Pass 2: remove self-references ---
                // Items that list themselves as their own ingredient cause immediate
                // infinite recursion. Explicitly strip them out.
                // Known offenders: "White Wool", "Beastmaster Crest", "Aspect of the Leech"
                if (name.equals(output)) continue;
                cleaned.put(name, ie.getValue());
            }

            if (cleaned.isEmpty()) continue;

            out.put(output, cleaned);
        }

        // --- Pass 3: DFS cycle-breaker safety net ---
        // Catches any remaining cycles not covered by the explicit rules above
        // (e.g., newly added remote recipes that introduce new circular paths).
        // When a back-edge is found, the ingredient edge creating the cycle is removed.
        breakRemainingCycles(out);

        return out;
    }

    /**
     * Performs a DFS over the recipe graph and removes the specific ingredient edge
     * that creates each detected cycle, leaving the rest of the recipe intact.
     */
    private void breakRemainingCycles(Map<String, Map<String, Integer>> recipes) {
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new LinkedHashSet<>();
        for (String start : new ArrayList<>(recipes.keySet())) {
            if (!visited.contains(start)) {
                dfsCycleBreak(start, recipes, visited, inStack);
            }
        }
    }

    private void dfsCycleBreak(String node, Map<String, Map<String, Integer>> recipes,
                                Set<String> visited, Set<String> inStack) {
        visited.add(node);
        inStack.add(node);
        Map<String, Integer> ingredients = recipes.get(node);
        if (ingredients != null) {
            for (String ingredient : new ArrayList<>(ingredients.keySet())) {
                if (!recipes.containsKey(ingredient)) continue; // leaf — no onward cycle possible
                if (inStack.contains(ingredient)) {
                    // Back-edge detected: node -> ingredient where ingredient is an ancestor.
                    // Remove this single edge to break the cycle without discarding the whole recipe.
                    ingredients.remove(ingredient);
                } else if (!visited.contains(ingredient)) {
                    dfsCycleBreak(ingredient, recipes, visited, inStack);
                }
            }
        }
        inStack.remove(node);
    }


    public static class RecipeNode {
        public String name;
        /** In shopping-list trees: how many are still missing. Otherwise: how many are needed. */
        public int amount;
        /** How many this step needs in total, before counting what the player already has. */
        public int required;
        /**
         * Shopping-list trees only: of the amount not missing, how many you don't hold yet but can craft from
         * materials you have.
         */
        public int toCraft;
        /**
         * Shopping-list trees only: forge time still ahead for this step and everything under it, every craft
         * one after another. {@code forgeMs} covers crafts not started yet; {@code forgeCookingEnds} holds when
         * each item cooking in the Forge now (that this step needs) is done, see {@link #forgeLeft(long)}.
         */
        public long forgeMs;
        /** Shopping-list trees only: held enough only by counting copies still cooking in the Forge. */
        public boolean cooking;
        public long[] forgeCookingEnds = new long[0];
        public List<RecipeNode> ingredients;

        public long forgeLeft(long now) {
            long left = forgeMs;
            for (long end : forgeCookingEnds) left += Math.max(0, end - now);
            return left;
        }

        public RecipeNode(String name, int amount, List<RecipeNode> ingredients) {
            this(name, amount, amount, ingredients);
        }

        public RecipeNode(String name, int amount, int required, List<RecipeNode> ingredients) {
            this.name = name;
            this.amount = amount;
            this.required = required;
            this.ingredients = ingredients;
        }
    }
}