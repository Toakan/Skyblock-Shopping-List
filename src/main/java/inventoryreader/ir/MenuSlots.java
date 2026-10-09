package inventoryreader.ir;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Helpers for reading the items in an open menu. */
public final class MenuSlots {
    /** The last 36 slots of every container menu are the player's own inventory. */
    private static final int PLAYER_SLOTS = 36;

    private MenuSlots() {}

    /** The non-empty stacks in the menu itself, without the player's inventory below it. */
    public static List<ItemStack> containerStacks(AbstractContainerMenu menu) {
        List<Slot> slots = menu.slots;
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < slots.size() - PLAYER_SLOTS; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (!stack.isEmpty()) out.add(stack);
        }
        return out;
    }
}
