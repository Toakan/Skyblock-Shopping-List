package inventoryreader.ir;

import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** The Settings screen, built with YetAnotherConfigLib. Values are stored by {@link SandboxWidget} on Save. */
public final class ConfigScreen {
    private ConfigScreen() {}

    public static Screen create(Screen parent) {
        SandboxWidget widget = SandboxWidget.getInstance();
        return YetAnotherConfigLib.createBuilder()
            .title(Component.literal(InventoryReader.NAME))
            .category(ConfigCategory.createBuilder()
                .name(Component.literal("HUD"))
                .option(toggle("HUD", "Show the shopping list on screen. Also toggled with H.",
                    widget::isEnabled, widget::setEnabled))
                .option(toggle("Show remaining",
                    "ON: show how many of each item are still left to gather. Red = none yet, orange = some, "
                        + "yellow = can craft from what you have, green = done.\n"
                        + "OFF: show the full amounts the recipes need, red until complete.",
                    widget::isShowRemaining, widget::setShowRemaining))
                .option(toggle("Total section",
                    "Show a Total at the top of the HUD with every raw material still needed across all recipes.",
                    widget::isShowTotal, widget::setShowTotal))
                .option(Option.<Integer>createBuilder()
                    .name(Component.literal("Max recipes"))
                    .description(OptionDescription.of(Component.literal(
                        "How many recipes the shopping list can hold.")))
                    .binding(3, widget::getMaxRecipes, widget::setMaxRecipes)
                    .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                        .range(1, SandboxWidget.MAX_RECIPES_LIMIT).step(1))
                    .build())
                .option(ButtonOption.createBuilder()
                    .name(Component.literal("Move HUD..."))
                    .description(OptionDescription.of(Component.literal(
                        "Drag the HUD to move it, drag a corner to resize. Also opened with B.")))
                    .action((screen, button) -> Minecraft.getInstance().gui.setScreen(new HudPositionScreen(screen)))
                    .build())
                .option(ButtonOption.createBuilder()
                    .name(Component.literal("Reset HUD position"))
                    .description(OptionDescription.of(Component.literal("Put the HUD back in the top-left corner.")))
                    .action((screen, button) -> widget.setWidgetPosition(10, 40))
                    .build())
                .build())
            .category(ConfigCategory.createBuilder()
                .name(Component.literal("Notifications"))
                .option(toggle("Pop-ups", "Pop-up when a recipe is ready to craft, and when you have made it.",
                    widget::isNotifications, widget::setNotifications))
                .option(toggle("Auto-remove",
                    "Take a recipe off the list once you have made the amount you asked for.",
                    widget::isAutoRemove, widget::setAutoRemove))
                .option(toggle("Sack reminder",
                    "Chat warning when your sacks haven't been opened for an hour. Between opens, sack counts "
                        + "follow the [Sacks] chat summaries and can drift.",
                    widget::isStaleSackWarning, widget::setStaleSackWarning))
                .build())
            .category(ConfigCategory.createBuilder()
                .name(Component.literal("Controls"))
                .option(ButtonOption.createBuilder()
                    .name(Component.literal("Key binds..."))
                    .description(OptionDescription.of(Component.literal(
                        "Rebind keys. This mod's keys are under \"" + InventoryReader.NAME + "\".")))
                    .action((screen, button) -> Minecraft.getInstance().gui.setScreen(
                        new KeyBindsScreen(screen, Minecraft.getInstance().options)))
                    .build())
                .build())
            // Each setter saves the widget config itself.
            .save(() -> {})
            .build()
            .generateScreen(parent);
    }

    private static Option<Boolean> toggle(String name, String description, Supplier<Boolean> getter, Consumer<Boolean> setter) {
        return Option.<Boolean>createBuilder()
            .name(Component.literal(name))
            .description(OptionDescription.of(Component.literal(description)))
            .binding(true, getter, setter)
            .controller(opt -> BooleanControllerBuilder.create(opt).onOffFormatter().coloured(true))
            .build();
    }
}
