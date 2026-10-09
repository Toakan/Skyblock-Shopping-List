package inventoryreader.ir;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Remembers the contents of each storage container (backpacks, ender chest pages, forge, accessory bag, Pets menu pages,
 * wardrobe pages, worn equipment) by title, and passes changes since the last time it was seen to {@link ResourcesManager}.
 */
public class StorageReader {
    private static final Type TYPE = new TypeToken<Map<String, Map<String, Integer>>>() {}.getType();
    private static final StorageReader INSTANCE = new StorageReader();
    /** The Pets menu, one title per page: "Pets" or "(1/3) Pets". */
    private static final Pattern PETS_MENU = Pattern.compile("^(\\(\\d+/\\d+\\) )?Pets$");
    /** Wardrobe pages: "Armor Sets" or "(2/3) Equipment Sets" (the titles Skyblocker matches). */
    private static final Pattern WARDROBE = Pattern.compile("^(\\(\\d+/\\d+\\) )?(Armor Sets|Equipment Sets)$");
    /** The menu showing worn equipment (necklace, cloak, belt, gloves). */
    private static final String EQUIPMENT_MENU = "Stats & Equipment";

    private Map<String, Map<String, Integer>> containers;

    private StorageReader() {}

    public static StorageReader getInstance() {
        return INSTANCE;
    }

    public static boolean isTrackedContainer(String title) {
        return title.contains("Backpack") || title.contains("Ender Chest")
            || title.contains("The Forge") || title.contains("Accessory Bag") || isPetsMenu(title)
            || isWearableMenu(title);
    }

    /** Wardrobe pages and the worn-equipment menu. */
    private static boolean isWearableMenu(String title) {
        return WARDROBE.matcher(title).matches() || title.startsWith(EQUIPMENT_MENU);
    }

    private static boolean isPetsMenu(String title) {
        return PETS_MENU.matcher(title).matches();
    }

    private Map<String, Map<String, Integer>> containers() {
        if (containers == null) {
            Map<String, Map<String, Integer>> loaded = JsonFiles.read(FilePathManager.containerJson(), TYPE);
            containers = loaded != null ? new HashMap<>(loaded) : new HashMap<>();
        }
        return containers;
    }

    /** Forgets the in-memory copy (after a reset deleted the file). */
    public synchronized void clear() {
        containers = null;
    }

    public synchronized void saveContainerContents(AbstractContainerMenu handler, String title) {
        if (!isTrackedContainer(title)) return;

        Map<String, Integer> newData = new LinkedHashMap<>();
        // The Pets menu also holds buttons (sort, convert, close...); only the pets count.
        boolean petsOnly = isPetsMenu(title);
        // These menus mix items with buttons; only real SkyBlock items count.
        boolean wearable = isWearableMenu(title);
        boolean itemsOnly = wearable || title.contains("Accessory Bag");
        // Worn items show in these menus too but are already counted (armor with the inventory, equipment
        // from the equipment menu); skipping them keeps them from counting twice.
        Set<String> worn = wearable ? wornNames(title) : Set.of();
        // The player's own inventory (below the menu) is tracked separately.
        for (ItemStack stack : MenuSlots.containerStacks(handler)) {
            if (petsOnly ? !ItemIds.isPet(stack) : itemsOnly && !ItemIds.hasSkyblockId(stack)) continue;
            if (wearable && ItemIds.isPet(stack)) continue;
            String name = ItemIds.nameOf(stack);
            if (worn.contains(name)) continue;
            newData.merge(name, stack.getCount(), Integer::sum);
        }

        Map<String, Integer> previous = containers().getOrDefault(title, Map.of());
        if (previous.equals(newData)) return;

        Map<String, Integer> changes = Counts.diff(previous, newData);

        containers.put(title, newData);
        Map<String, Map<String, Integer>> copy = new HashMap<>(containers);
        JsonFiles.writeAsync(FilePathManager.containerJson(), () -> copy);
        ResourcesManager.getInstance().saveData(changes);
    }

    /** Names of the armor being worn, plus (outside the equipment menu itself) the worn equipment. */
    private Set<String> wornNames(String title) {
        Set<String> names = new HashSet<>();
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            Inventory inventory = client.player.getInventory();
            for (int i = Inventory.INVENTORY_SIZE; i < Inventory.SLOT_OFFHAND; i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()) names.add(ItemIds.nameOf(stack));
            }
        }
        if (!title.startsWith(EQUIPMENT_MENU)) {
            containers().forEach((key, items) -> {
                if (key.startsWith(EQUIPMENT_MENU)) names.addAll(items.keySet());
            });
        }
        return names;
    }
}
