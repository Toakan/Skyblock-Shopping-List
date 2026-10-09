package inventoryreader.ir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Names items by their SkyBlock ID instead of their shown name. Hypixel stores the ID as "id" in the
 * item's custom data; the shown name can carry a reforge ("Refined Titanium Drill DR-X355") or other
 * decoration that would not match the recipe name.
 */
public final class ItemIds {
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();
    private static final String PET_ID = "PET";
    /** Pet rarities in the order of the NEU pet ID suffix ("BEE;4" = Legendary Bee). */
    private static final List<String> PET_TIERS = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC");
    private static volatile Map<String, String> namesById;
    /**
     * SkyBlock ID per stack. Reading it copies the item's whole custom data (26.2 only offers copyTag()), and
     * the inventory is scanned every 2 ticks. ItemStack uses identity equality and Hypixel sends a new stack
     * whenever an item's data changes, so the ID of a stack never goes stale; entries vanish with the stack.
     */
    private static final Map<ItemStack, String> ID_CACHE = new WeakHashMap<>();

    private ItemIds() {}

    /** Re-reads the ID table (after the recipe fetch rewrote it). */
    public static void reload() {
        Map<String, String> loaded = JsonFiles.read(FilePathManager.ITEM_NAMES_JSON, MAP_TYPE);
        namesById = loaded != null ? Map.copyOf(loaded) : Map.of();
    }

    /** The item's SkyBlock ID (pets in NEU form), from the cache when this stack was seen before. */
    private static String cachedId(ItemStack stack) {
        synchronized (ID_CACHE) {
            String id = ID_CACHE.get(stack);
            if (id == null) {
                id = skyblockId(stack);
                ID_CACHE.put(stack, id);
            }
            return id;
        }
    }

    /** The recipe name for this item if its SkyBlock ID is known, otherwise its shown name. */
    public static String nameOf(ItemStack stack) {
        String id = cachedId(stack);
        if (!id.isEmpty()) {
            Map<String, String> names = namesById;
            if (names == null) {
                reload();
                names = namesById;
            }
            String name = names.get(id);
            if (name != null) return name;
        }
        return stack.getHoverName().getString();
    }

    /** True for a real SkyBlock item; menu buttons ("Go Back", "Next Page", glass panes) have no ID. */
    public static boolean hasSkyblockId(ItemStack stack) {
        return !cachedId(stack).isEmpty();
    }

    /** True for a SkyBlock pet item. */
    public static boolean isPet(ItemStack stack) {
        // Pets get the NEU form "TYPE;rarity" (only pets get a ';'); one whose info couldn't be read keeps "PET".
        String id = cachedId(stack);
        return id.equals(PET_ID) || id.indexOf(';') >= 0;
    }

    /**
     * The item's SkyBlock ID. Every pet has the ID "PET" with its type and rarity in the "petInfo" JSON, so pets
     * get the NEU form "TYPE;rarity" ("BEE;4") that the recipe tables use.
     */
    private static String skyblockId(ItemStack stack) {
        CompoundTag data = customData(stack);
        String id = data.getStringOr("id", "");
        if (!PET_ID.equals(id)) return id;
        try {
            JsonObject info = JsonParser.parseString(data.getStringOr("petInfo", "")).getAsJsonObject();
            int tier = PET_TIERS.indexOf(info.get("tier").getAsString().toUpperCase(Locale.ROOT));
            if (tier >= 0) return info.get("type").getAsString() + ";" + tier;
        } catch (RuntimeException e) {
            // Missing or unexpected petInfo; fall back to the shown name.
        }
        return id;
    }

    private static CompoundTag customData(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }
}
