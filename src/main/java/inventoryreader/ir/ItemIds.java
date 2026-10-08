package inventoryreader.ir;

import com.google.gson.reflect.TypeToken;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.lang.reflect.Type;
import java.util.Map;

/**
 * Names items by their SkyBlock ID instead of their shown name. Hypixel stores the ID as "id" in the
 * item's custom data; the shown name can carry a reforge ("Refined Titanium Drill DR-X355") or other
 * decoration that would not match the recipe name.
 */
public final class ItemIds {
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();
    private static volatile Map<String, String> namesById;

    private ItemIds() {}

    /** Re-reads the ID table (after the recipe fetch rewrote it). */
    public static void reload() {
        Map<String, String> loaded = JsonFiles.read(FilePathManager.ITEM_NAMES_JSON, MAP_TYPE);
        namesById = loaded != null ? Map.copyOf(loaded) : Map.of();
    }

    /** The recipe name for this item if its SkyBlock ID is known, otherwise its shown name. */
    public static String nameOf(ItemStack stack) {
        String id = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getStringOr("id", "");
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
}
