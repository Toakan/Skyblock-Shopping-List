package inventoryreader.ir.mixin;

import inventoryreader.ir.SkyblockDetector;
import inventoryreader.ir.StorageReader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-reads tracked storage menus (backpacks, ender chest, ...) after the player moves items in them. Observes the click
 * only: it never cancels or changes it.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class SlotClickMixin {

    @Inject(method = "clicked", at = @At("RETURN"))
    private void onClickSlotReturn(int slotIndex, int button, ContainerInput input, Player player, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        // In singleplayer the integrated server's copy of the menu runs this too; only read the client's.
        if (!client.isSameThread() || !SkyblockDetector.isTracking()) return;
        Screen screen = client.gui.screen();
        if (screen == null) return;
        String title = screen.getTitle().getString();
        // Same menus as the regular re-read; this just updates right after a click instead of up to 10 ticks later.
        if (StorageReader.isTrackedContainer(title)) {
            StorageReader.getInstance().saveContainerContents((AbstractContainerMenu) (Object) this, title);
        }
    }
}
