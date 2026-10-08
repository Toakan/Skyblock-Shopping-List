package inventoryreader.ir;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Remembers the contents of each storage container (backpacks, ender chest pages, forge, accessory bag)
 * by title, and passes changes since the last time it was seen to {@link ResourcesManager}.
 */
public class StorageReader {
    private static final Type TYPE = new TypeToken<Map<String, Map<String, Integer>>>() {}.getType();
    private static final StorageReader INSTANCE = new StorageReader();

    private Map<String, Map<String, Integer>> containers;

    private StorageReader() {}

    public static StorageReader getInstance() {
        return INSTANCE;
    }

    public static boolean isTrackedContainer(String title) {
        return title.contains("Backpack") || title.contains("Ender Chest")
            || title.contains("The Forge") || title.contains("Accessory Bag");
    }

    private Map<String, Map<String, Integer>> containers() {
        if (containers == null) {
            Map<String, Map<String, Integer>> loaded = JsonFiles.read(FilePathManager.CONTAINER_JSON, TYPE);
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
        List<Slot> slots = handler.slots;
        // The last 36 slots are the player's own inventory, which is tracked separately.
        for (int i = 0; i < slots.size() - 36; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (!stack.isEmpty()) {
                newData.merge(stack.getHoverName().getString(), stack.getCount(), Integer::sum);
            }
        }

        Map<String, Integer> previous = containers().getOrDefault(title, Map.of());
        if (previous.equals(newData)) return;

        Map<String, Integer> changes = new HashMap<>();
        newData.forEach((name, count) -> {
            int delta = count - previous.getOrDefault(name, 0);
            if (delta != 0) changes.put(name, delta);
        });
        previous.forEach((name, count) -> {
            if (!newData.containsKey(name) && count != 0) changes.put(name, -count);
        });

        containers.put(title, newData);
        JsonFiles.write(FilePathManager.CONTAINER_JSON, containers);
        ResourcesManager.getInstance().saveData(changes);
    }
}
