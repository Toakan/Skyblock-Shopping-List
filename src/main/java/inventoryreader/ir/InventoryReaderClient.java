package inventoryreader.ir;

import com.google.gson.reflect.TypeToken;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

public class InventoryReaderClient implements ClientModInitializer {
    private static final String INVENTORY_KEY = "Player Inventory";
    private static final Type DATA_TYPE = new TypeToken<Map<String, Map<String, Integer>>>() {}.getType();

    /** Own section in Options > Controls > Key Binds; label from key.category.skyblock-shopping-list.main. */
    private static final KeyMapping.Category KEY_CATEGORY =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath(InventoryReader.MOD_ID, "main"));
    private static KeyMapping openMenuKey;
    private static KeyMapping moveHudKey;
    private static KeyMapping toggleWidgetKey;
    /** Set by commands; the screen opens on the next tick, after the chat screen has closed. */
    public static boolean shouldOpenMenu = false;
    public static boolean shouldOpenMoveHud = false;

    /** Last inventory contents written to inventorydata.json; null until loaded. */
    private static Map<String, Integer> lastInventory;
    private int tickCounter = 0;

    @Override
    public void onInitializeClient() {
        FilePathManager.initialize();

        // Key ids are kept from earlier versions so existing bindings in options.txt carry over.
        openMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.skyblock-shopping-list.open_sandbox_viewer", GLFW.GLFW_KEY_V, KEY_CATEGORY));
        moveHudKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.skyblock-shopping-list.open_widget_customization", GLFW.GLFW_KEY_B, KEY_CATEGORY));
        toggleWidgetKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.skyblock-shopping-list.toggle_widget", GLFW.GLFW_KEY_H, KEY_CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Keys only act on SkyBlock (presses are still consumed so they don't queue up);
            // the /ssl commands work anywhere.
            boolean onSkyblock = SkyblockDetector.isOnSkyblock();
            if ((openMenuKey.consumeClick() && onSkyblock) || shouldOpenMenu) {
                shouldOpenMenu = false;
                MenuTabs.open(MenuTabs.Tab.SHOPPING_LIST);
            }
            if ((moveHudKey.consumeClick() && onSkyblock) || shouldOpenMoveHud) {
                shouldOpenMoveHud = false;
                client.gui.setScreen(new HudPositionScreen(null));
            }
            if (toggleWidgetKey.consumeClick() && onSkyblock) {
                SandboxWidget widget = SandboxWidget.getInstance();
                widget.setEnabled(!widget.isEnabled());
            }
            // Other servers' inventories must never change the SkyBlock counts.
            // Waits for the profile after a server change, so its items aren't booked to the last one.
            if (SkyblockDetector.isTracking() && client.player != null && client.level != null && ++tickCounter >= 2) {
                tickCounter = 0;
                checkInventory(client);
            }
        });

        SkyblockDetector.register();
        StorageViewerMod.register();
        IrCommandManager.register();
        SackChatListener.register();
        ProfileManager.register();
        ProfileChatListener.register();
        // Saves run on a background thread; make sure the last ones reach the disk before the game closes.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> JsonFiles.flush());
        CoinTracker.register();
        ReminderManager.initialize();
        WelcomeManager.initialize();
        SandboxWidget.getInstance();
        HudPresets.migrate();
        InventoryReader.debug("Initialized {} client components", InventoryReader.NAME);
    }

    /** Forgets the remembered inventory (after a reset deleted the file). */
    public static void clearInventorySnapshot() {
        lastInventory = null;
    }

    private static void checkInventory(Minecraft client) {
        Inventory inventory = client.player.getInventory();
        Map<String, Integer> current = new HashMap<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                current.merge(ItemIds.nameOf(stack), stack.getCount(), Integer::sum);
            }
        }

        if (lastInventory == null) {
            Map<String, Map<String, Integer>> saved = JsonFiles.read(FilePathManager.inventoryJson(), DATA_TYPE);
            Map<String, Integer> previous = saved != null ? saved.get(INVENTORY_KEY) : null;
            lastInventory = previous != null ? previous : new HashMap<>();
        }
        if (current.equals(lastInventory)) return;

        Map<String, Integer> changes = new HashMap<>();
        current.forEach((name, count) -> {
            int delta = count - lastInventory.getOrDefault(name, 0);
            if (delta != 0) changes.put(name, delta);
        });
        lastInventory.forEach((name, count) -> {
            if (!current.containsKey(name) && count != 0) changes.put(name, -count);
        });

        lastInventory = current;
        Map<String, Map<String, Integer>> data = new HashMap<>();
        data.put(INVENTORY_KEY, current);
        JsonFiles.writeAsync(FilePathManager.inventoryJson(), () -> data);
        ResourcesManager.getInstance().saveData(changes);
    }
}
