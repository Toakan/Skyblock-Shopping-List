package inventoryreader.ir;

import java.util.List;
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

    private SackHighlight() {}

    /** Called for each slot as the container screen draws it, in the screen's slot coordinates. */
    public static void drawBehind(GuiGraphicsExtractor context, Screen screen, Slot slot) {
        if (!SkyblockDetector.isTracking() || slot.container instanceof Inventory) return;
        if (!screen.getTitle().getString().contains("Sack")) return;
        List<String> needed = SandboxWidget.getInstance().getNeededSacks();
        if (needed.isEmpty()) return;
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) return;
        String name = SIZE.matcher(ItemNames.clean(stack.getHoverName().getString())).replaceFirst("");
        if (needed.contains(name)) context.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, COLOR);
    }
}
