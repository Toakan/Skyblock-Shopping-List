package inventoryreader.ir;

import com.google.gson.reflect.TypeToken;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

public class InventoryReaderClient implements ClientModInitializer {
    private static final String INVENTORY_KEY = "Player Inventory";
    private static final Type DATA_TYPE = new TypeToken<Map<String, Map<String, Integer>>>() {}.getType();

    private static KeyMapping openSandboxViewerKey;
    private static KeyMapping openWidgetCustomizationKey;
    private static KeyMapping toggleWidgetKey;
    private static KeyMapping openPositioningHudKey;
    public static boolean shouldOpenSandboxViewer = false;
    public static boolean shouldOpenWidgetCustomization = false;

    /** Last inventory contents written to inventorydata.json; null until loaded. */
    private static Map<String, Integer> lastInventory;
    private int tickCounter = 0;

    @Override
    public void onInitializeClient() {
        FilePathManager.initialize();

        openSandboxViewerKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.skyblock-shopping-list.open_sandbox_viewer", GLFW.GLFW_KEY_V, KeyMapping.Category.MISC));
        openWidgetCustomizationKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.skyblock-shopping-list.open_widget_customization", GLFW.GLFW_KEY_B, KeyMapping.Category.MISC));
        toggleWidgetKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.skyblock-shopping-list.toggle_widget", GLFW.GLFW_KEY_H, KeyMapping.Category.MISC));
        openPositioningHudKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.skyblock-shopping-list.open_positioning_hud", GLFW.GLFW_KEY_J, KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (openSandboxViewerKey.consumeClick() || shouldOpenSandboxViewer) {
                shouldOpenSandboxViewer = false;
                client.gui.setScreen(new SandboxViewer());
            }
            if (openWidgetCustomizationKey.consumeClick() || shouldOpenWidgetCustomization) {
                shouldOpenWidgetCustomization = false;
                client.gui.setScreen(new WidgetCustomizationMenu());
            }
            if (toggleWidgetKey.consumeClick()) {
                SandboxWidget widget = SandboxWidget.getInstance();
                widget.setEnabled(!widget.isEnabled());
            }
            if (openPositioningHudKey.consumeClick()) {
                client.gui.setScreen(new WidgetCustomizationMenu(true));
            }
            if (client.player != null && client.level != null && ++tickCounter >= 2) {
                tickCounter = 0;
                checkInventory(client);
            }
        });

        StorageViewerMod.register();
        IrCommandManager.register();
        SackChatListener.register();
        ReminderManager.initialize();
        WelcomeManager.initialize();
        SandboxWidget.getInstance();
        InventoryReader.LOGGER.info("Initialized {} client components", InventoryReader.NAME);
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
            Map<String, Map<String, Integer>> saved = JsonFiles.read(FilePathManager.INVENTORY_JSON, DATA_TYPE);
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
        JsonFiles.write(FilePathManager.INVENTORY_JSON, data);
        ResourcesManager.getInstance().saveData(changes);
    }
}
