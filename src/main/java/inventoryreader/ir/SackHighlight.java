package inventoryreader.ir;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Marks, in sack menus, the sacks the shopping list still needs read: a gold square behind each one. Drawing
 * only; clicks are untouched.
 */
public final class SackHighlight {
    /** Behind the item, so the icon stays clear. */
    private static final int COLOR = 0x80FFAA00;
    /** Sack items come in sizes ("Large Enchanted Mining Sack"); the sack list names them without. */
    private static final Pattern SIZE = Pattern.compile("^(Small|Medium|Large) ");

    /**
     * Render thread only. Sack name per stack, worked out once: ItemStack uses identity equality and Hypixel
     * sends a new stack when an item changes, so a name never goes stale; entries vanish with the stack.
     */
    private static final Map<ItemStack, String> NAMES = new WeakHashMap<>();
    /** Render thread only: the screen last checked, and whether its title makes it a sack menu. */
    private static Screen lastScreen;
    private static boolean lastIsSackMenu;

    private SackHighlight() {}

    /** Called for each slot as the container screen draws it, in the screen's slot coordinates. */
    public static void drawBehind(GuiGraphicsExtractor context, Screen screen, Slot slot) {
        if (!SkyblockDetector.isTracking() || slot.container instanceof Inventory) return;
        if (screen != lastScreen) {
            lastScreen = screen;
            // Any menu with "Sack" in its title counts: the sack list and each sack's own menu.
            lastIsSackMenu = screen.getTitle().getString().contains("Sack");
        }
        if (!lastIsSackMenu) return;
        List<String> needed = SandboxWidget.getInstance().getNeededSacks();
        if (needed.isEmpty()) return;
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) return;
        String name = NAMES.computeIfAbsent(stack,
            s -> SIZE.matcher(ItemNames.clean(s.getHoverName().getString())).replaceFirst(""));
        if (needed.contains(name)) context.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, COLOR);
    }
}
