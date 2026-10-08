package inventoryreader.ir;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;

/** Mod settings. Every change is applied and saved immediately. */
public class SettingsScreen extends Screen {
    private static final int GOLD = 0xFFFFB728;
    private static final int LABEL = 0xFFDDDDDD;
    private static final int COLUMN_WIDTH = 150;
    private static final int COLUMN_GAP = 10;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_STEP = 24;
    private static final int TOP = 50;

    private final Screen parent;
    private final SandboxWidget widget = SandboxWidget.getInstance();
    private int maxRecipesLabelX;
    private int maxRecipesLabelY;

    public SettingsScreen(Screen parent) {
        super(Component.literal(InventoryReader.NAME + " Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - COLUMN_WIDTH - COLUMN_GAP / 2;
        int right = this.width / 2 + COLUMN_GAP / 2;

        // Left column: on/off options.
        int y = TOP;
        toggle(left, y, "HUD", widget::isEnabled, widget::setEnabled,
            "Show the shopping list on screen. Also toggled with H.");
        y += ROW_STEP;
        toggle(left, y, "Show remaining", widget::isShowRemaining, widget::setShowRemaining,
            "ON: show how many of each item are still left to gather. Red = none yet, orange = some, green = done.\n"
            + "OFF: show the full amounts the recipes need, red until complete.");
        y += ROW_STEP;
        toggle(left, y, "Total section", widget::isShowTotal, widget::setShowTotal,
            "Show a Total at the top of the HUD with every raw material still needed across all recipes.");
        y += ROW_STEP;
        toggle(left, y, "Notifications", widget::isNotifications, widget::setNotifications,
            "Pop-up when a recipe is ready to craft, and when you have made it.");
        y += ROW_STEP;
        toggle(left, y, "Auto-remove", widget::isAutoRemove, widget::setAutoRemove,
            "Take a recipe off the list once you have made the amount you asked for.");

        // Right column: list size and other screens.
        y = TOP;
        maxRecipesLabelX = right;
        maxRecipesLabelY = y + 6;
        this.addRenderableWidget(Button.builder(Component.literal("-"),
            button -> widget.setMaxRecipes(widget.getMaxRecipes() - 1)
        ).bounds(right + COLUMN_WIDTH - 44, y, 20, ROW_HEIGHT).build());
        this.addRenderableWidget(Button.builder(Component.literal("+"),
            button -> widget.setMaxRecipes(widget.getMaxRecipes() + 1)
        ).bounds(right + COLUMN_WIDTH - 20, y, 20, ROW_HEIGHT)
          .tooltip(Tooltip.create(Component.literal("How many recipes the shopping list can hold (1-" + SandboxWidget.MAX_RECIPES_LIMIT + ").")))
          .build());
        y += ROW_STEP;
        this.addRenderableWidget(Button.builder(Component.literal("Edit shopping list / HUD..."),
            button -> this.minecraft.gui.setScreen(new WidgetCustomizationMenu())
        ).bounds(right, y, COLUMN_WIDTH, ROW_HEIGHT).build());
        y += ROW_STEP;
        this.addRenderableWidget(Button.builder(Component.literal("Reset HUD position"),
            button -> widget.setWidgetPosition(10, 40)
        ).bounds(right, y, COLUMN_WIDTH, ROW_HEIGHT).build());
        y += ROW_STEP;
        this.addRenderableWidget(Button.builder(Component.literal("Key binds..."),
            button -> this.minecraft.gui.setScreen(new KeyBindsScreen(this, this.minecraft.options))
        ).bounds(right, y, COLUMN_WIDTH, ROW_HEIGHT)
          .tooltip(Tooltip.create(Component.literal("Rebind keys. This mod's keys are under \"Skyblock Shopping List\".")))
          .build());

        this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
            .bounds(this.width / 2 - 50, this.height - 30, 100, ROW_HEIGHT).build());
    }

    private void toggle(int x, int y, String name, BooleanSupplier getter, Consumer<Boolean> setter, String tooltip) {
        this.addRenderableWidget(Button.builder(toggleLabel(name, getter.getAsBoolean()), button -> {
            setter.accept(!getter.getAsBoolean());
            button.setMessage(toggleLabel(name, getter.getAsBoolean()));
        }).bounds(x, y, COLUMN_WIDTH, ROW_HEIGHT)
          .tooltip(Tooltip.create(Component.literal(tooltip)))
          .build());
    }

    private static Component toggleLabel(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "ON" : "OFF"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.centeredText(this.font, this.title, this.width / 2, 20, GOLD);
        int count = widget.getShoppingList().size();
        context.centeredText(this.font, "Shopping list: " + count + (count == 1 ? " recipe" : " recipes"), this.width / 2, 34, LABEL);
        context.text(this.font, "Max recipes: " + widget.getMaxRecipes(), maxRecipesLabelX, maxRecipesLabelY, LABEL, false);
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
