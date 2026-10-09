package inventoryreader.ir;

import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Coin prices at NPCs: the cheapest coins-only shop price per item (NEU repo) and what an NPC pays for it (Hypixel's
 * item list). Both are written by {@link inventoryreader.ir.recipes.RemoteRecipeFetcher}; lookups go by item name.
 */
public final class NpcPrices {
    private static final Type MAP_TYPE = new TypeToken<Map<String, Double>>() {}.getType();
    private static volatile Map<String, Double> buy = Map.of();
    private static volatile Map<String, Double> sell = Map.of();
    /** Changes every time the prices are (re)loaded, so the HUD knows to recompute. */
    private static volatile long version = 0;

    private NpcPrices() {}

    /** Re-reads both price files. */
    public static void reload() {
        buy = load(FilePathManager.NPC_BUY_PRICES_JSON);
        sell = load(FilePathManager.NPC_SELL_PRICES_JSON);
        version++;
    }

    public static long getVersion() {
        return version;
    }

    /** Keyed by {@link ItemNames#normalize}, so names with and without symbol prefixes match. */
    private static Map<String, Double> load(File file) {
        Map<String, Double> loaded = JsonFiles.read(file, MAP_TYPE);
        if (loaded == null) return Map.of();
        Map<String, Double> out = new HashMap<>();
        loaded.forEach((name, price) -> {
            if (price != null && price > 0) out.putIfAbsent(ItemNames.normalize(name), price);
        });
        return Map.copyOf(out);
    }

    /**
     * "Buy" and "Sell" lines for these rows (name, how many; rows of the same item are added together): coins to buy them all at NPCs and coins NPCs pay for
     * them, each with how many of the items have a price. Coins on the list count as their own buy price and are left out of Sell. Empty when
     * nothing has a price.
     */
    public static List<String> summary(List<RecipeManager.RecipeNode> rows) {
        double buyTotal = 0;
        double sellTotal = 0;
        int buyPriced = 0;
        int sellPriced = 0;
        int count = 0;
        int coinRows = 0;
        Map<String, Double> buyNow = buy;
        Map<String, Double> sellNow = sell;
        Map<String, Long> required = new java.util.LinkedHashMap<>();
        for (RecipeManager.RecipeNode row : rows) {
            if (row.required > 0) required.merge(ItemNames.normalize(row.name), (long) row.required, Long::sum);
        }
        String coins = ItemNames.normalize(inventoryreader.ir.recipes.RemoteRecipeFetcher.COINS_NAME);
        for (Map.Entry<String, Long> row : required.entrySet()) {
            String key = row.getKey();
            long amount = row.getValue();
            count++;
            if (coins.equals(key)) {
                buyTotal += amount;
                buyPriced++;
                coinRows++;
                continue;
            }
            Double b = buyNow.get(key);
            if (b != null) { buyTotal += b * amount; buyPriced++; }
            Double s = sellNow.get(key);
            if (s != null) { sellTotal += s * amount; sellPriced++; }
        }
        if (buyPriced == 0 && sellPriced == 0) return List.of();
        return List.of(
            line("Buy", buyTotal, buyPriced, count),
            line("Sell", sellTotal, sellPriced, count - coinRows));
    }

    private static String line(String label, double coins, int priced, int count) {
        return String.format(java.util.Locale.ROOT, "%s: %,d coins (%d/%d items)", label, Math.round(coins), priced, count);
    }
}
