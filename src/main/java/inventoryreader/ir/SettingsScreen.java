package inventoryreader.ir;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;

/** Mod settings. Every change is applied and saved immediately. */
public class SettingsScreen extends Screen {
    private static final int GOLD = 0xFFFFB728;
    private static final int LABEL = 0xFFDDDDDD;
    private static final int ROW_WIDTH = 200;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_STEP = 26;

    private final Screen parent;
    private final SandboxWidget widget = SandboxWidget.getInstance();
    private int craftAmountLabelY;

    public SettingsScreen(Screen parent) {
        super(Component.literal(InventoryReader.NAME + " Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int x = this.width / 2 - ROW_WIDTH / 2;
        int y = 50;

        this.addRenderableWidget(Button.builder(hudLabel(), button -> {
            widget.setEnabled(!widget.isEnabled());
            button.setMessage(hudLabel());
        }).bounds(x, y, ROW_WIDTH, ROW_HEIGHT)
          .tooltip(Tooltip.create(Component.literal("Show the recipe shopping list on screen. Also toggled with H.")))
          .build());
        y += ROW_STEP;

        this.addRenderableWidget(Button.builder(showRemainingLabel(), button -> {
            widget.setShowRemaining(!widget.isShowRemaining());
            button.setMessage(showRemainingLabel());
        }).bounds(x, y, ROW_WIDTH, ROW_HEIGHT)
          .tooltip(Tooltip.create(Component.literal(
              "ON: show how many of each item are still left to gather. Red = none yet, orange = some, green = done.\n"
              + "OFF: show the full amounts the recipe needs, red until complete.")))
          .build());
        y += ROW_STEP;

        craftAmountLabelY = y + 6;
        EditBox craftAmount = new EditBox(this.font, x + ROW_WIDTH - 60, y, 60, ROW_HEIGHT, Component.literal("Craft amount"));
        craftAmount.setMaxLength(6);
        craftAmount.setValue(String.valueOf(widget.getCraftAmount()));
        craftAmount.setResponder(text -> {
            try {
                int amount = Integer.parseInt(text.trim());
                if (amount > 0 && amount != widget.getCraftAmount()) widget.setCraftAmount(amount);
            } catch (NumberFormatException ignored) {
                // Leave the saved amount alone while the box is empty or half-typed.
            }
        });
        this.addRenderableWidget(craftAmount);
        y += ROW_STEP;

        this.addRenderableWidget(Button.builder(Component.literal("Reset HUD position"),
            button -> widget.setWidgetPosition(10, 40)
        ).bounds(x, y, ROW_WIDTH, ROW_HEIGHT).build());
        y += ROW_STEP;

        this.addRenderableWidget(Button.builder(Component.literal("Choose recipe / move HUD..."),
            button -> this.minecraft.gui.setScreen(new WidgetCustomizationMenu())
        ).bounds(x, y, ROW_WIDTH, ROW_HEIGHT).build());
        y += ROW_STEP;

        this.addRenderableWidget(Button.builder(Component.literal("Key binds..."),
            button -> this.minecraft.gui.setScreen(new KeyBindsScreen(this, this.minecraft.options))
        ).bounds(x, y, ROW_WIDTH, ROW_HEIGHT)
          .tooltip(Tooltip.create(Component.literal("Rebind keys. This mod's keys are under \"Skyblock Shopping List\".")))
          .build());

        this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
            .bounds(this.width / 2 - 50, this.height - 30, 100, ROW_HEIGHT).build());
    }

    private Component hudLabel() {
        return Component.literal("HUD: " + (widget.isEnabled() ? "ON" : "OFF"));
    }

    private Component showRemainingLabel() {
        return Component.literal("Show remaining: " + (widget.isShowRemaining() ? "ON" : "OFF"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.centeredText(this.font, this.title, this.width / 2, 20, GOLD);
        String recipe = widget.getSelectedRecipe();
        context.centeredText(this.font, "HUD recipe: " + (recipe != null ? recipe : "none"), this.width / 2, 34, LABEL);
        context.text(this.font, "Craft amount", this.width / 2 - ROW_WIDTH / 2, craftAmountLabelY, LABEL, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
