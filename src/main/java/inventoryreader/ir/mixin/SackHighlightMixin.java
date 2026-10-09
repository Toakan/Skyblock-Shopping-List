package inventoryreader.ir.mixin;

import inventoryreader.ir.SackHighlight;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the sack highlight behind a slot before the slot's item is drawn. Rendering only: it never cancels or
 * changes anything.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class SackHighlightMixin {

    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void beforeExtractSlot(GuiGraphicsExtractor context, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        SackHighlight.drawBehind(context, (Screen) (Object) this, slot);
    }
}
