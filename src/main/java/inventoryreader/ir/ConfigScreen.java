package inventoryreader.ir;

import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.ColorControllerBuilder;
import dev.isxander.yacl3.api.controller.DropdownStringControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.FloatSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;

import java.awt.Color;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
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
                        "Drag a panel to move it, a corner to scale it, an edge to resize it. Also opened with B.")))
                    .action((screen, button) -> Minecraft.getInstance().gui.setScreen(new HudPositionScreen(screen)))
                    .build())
                .option(ButtonOption.createBuilder()
                    .name(Component.literal("Reset HUD position"))
                    .description(OptionDescription.of(Component.literal("Put the HUD back in the top-left corner.")))
                    .action((screen, button) -> widget.resetPanelPositions())
                    .build())
                .build())
            .category(appearance(parent))
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
            // Widget setters save the widget config themselves; Appearance values are written here.
            .save(HudStyle::save)
            .build()
            .generateScreen(parent);
    }

    /** Settings > Appearance: every {@link HudStyle} value, grouped. */
    private static ConfigCategory appearance(Screen parent) {
        HudStyle style = HudStyle.get();
        HudStyle d = new HudStyle();
        List<String> fonts = HudStyle.availableFonts();
        if (!fonts.contains(style.font)) fonts.add(style.font);
        return ConfigCategory.createBuilder()
            .name(Component.literal("Appearance"))
            .option(ButtonOption.createBuilder()
                .name(Component.literal("Preview (Move HUD...)"))
                .description(OptionDescription.of(Component.literal(
                    "Shows the HUD with the saved look. Press Save first to see changes.")))
                .action((screen, button) -> Minecraft.getInstance().gui.setScreen(new HudPositionScreen(screen)))
                .build())
            .option(ButtonOption.createBuilder()
                .name(Component.literal("Reset look to defaults"))
                .description(OptionDescription.of(Component.literal(
                    "Puts every Appearance setting back to the original look, right away. Your current look is "
                        + "kept as the preset \"" + HudPresets.BACKUP_NAME + "\".")))
                .action((screen, button) -> {
                    HudPresets.backup();
                    HudStyle.reset();
                    Minecraft.getInstance().gui.setScreen(create(parent));
                })
                .build())
            .group(presets(parent))
            .group(OptionGroup.createBuilder()
                .name(Component.literal("Size"))
                .option(slider("HUD scale", "Size of every HUD panel, in percent. 100% is the original size.", 50, 300,
                    d.hudScale, () -> style.hudScale, v -> style.hudScale = v))
                .option(toggle("Follow GUI Scale",
                    "OFF: the HUD keeps its size whatever Minecraft's GUI Scale is set to.\n"
                        + "ON: the HUD grows and shrinks with GUI Scale like the rest of the interface.",
                    d.followGuiScale, () -> style.followGuiScale, v -> style.followGuiScale = v))
                .build())
            .group(OptionGroup.createBuilder()
                .name(Component.literal("Text"))
                .option(Option.<String>createBuilder()
                    .name(Component.literal("Font"))
                    .description(OptionDescription.of(Component.literal(
                        "Fonts from Minecraft and your enabled resource packs. If a pack's font is removed, the "
                            + "HUD falls back to the default font.")))
                    .binding(d.font, () -> style.font, v -> style.font = v)
                    .controller(opt -> DropdownStringControllerBuilder.create(opt).values(fonts))
                    .build())
                .option(size("Title size", "Size of the panel titles.", d.titleScale,
                    () -> style.titleScale, v -> style.titleScale = v))
                .option(align("Title alignment", d.titleAlign, () -> style.titleAlign, v -> style.titleAlign = v))
                .option(size("Row text size", "Size of the recipe rows. Rows stay left-aligned so the tree lines up.",
                    d.textScale, () -> style.textScale, v -> style.textScale = v))
                .option(size("Craftable text size", "Size of the Craftable section.", d.craftableScale,
                    () -> style.craftableScale, v -> style.craftableScale = v))
                .option(align("Craftable alignment", d.craftableAlign, () -> style.craftableAlign, v -> style.craftableAlign = v))
                .option(size("Forging text size", "Size of the Forging section.", d.forgingScale,
                    () -> style.forgingScale, v -> style.forgingScale = v))
                .option(align("Forging alignment", d.forgingAlign, () -> style.forgingAlign, v -> style.forgingAlign = v))
                .option(toggle("Text shadow", "Draw a shadow under HUD text.", d.textShadow,
                    () -> style.textShadow, v -> style.textShadow = v))
                .option(toggle("Bold recipe names", "Show the recipes on your list (top rows) in bold.", d.boldRootNames,
                    () -> style.boldRootNames, v -> style.boldRootNames = v))
                .build())
            .group(OptionGroup.createBuilder()
                .name(Component.literal("Parts"))
                .option(Option.<HudStyle.AmountFormat>createBuilder()
                    .name(Component.literal("Amount format"))
                    .description(OptionDescription.of(Component.literal(
                        "Remaining: what is still left to get (3×).\nHave / need: what you hold of what is needed "
                            + "(83/5,120).\nRequired: the full amount the recipes need (6×).\n\nColours: red = none "
                            + "yet, orange = some, yellow = can craft from what you have, green = done.")))
                    .binding(d.amountFormat, () -> style.amountFormat, v -> style.amountFormat = v)
                    .controller(opt -> EnumControllerBuilder.create(opt).enumClass(HudStyle.AmountFormat.class)
                        .formatValue(v -> Component.literal(switch (v) {
                            case REMAINING -> "Remaining";
                            case HAVE_NEED -> "Have / need";
                            case REQUIRED -> "Required";
                        })))
                    .build())
                .option(toggle("Craftable section", "Show what you can craft right now.", d.showCraftable,
                    () -> style.showCraftable, v -> style.showCraftable = v))
                .option(placement("Craftable panel", d.craftablePlacement,
                    () -> style.craftablePlacement, v -> style.craftablePlacement = v))
                .option(toggle("Forging section", "Show what is cooking in your Forge that the list needs.", d.showForging,
                    () -> style.showForging, v -> style.showForging = v))
                .option(placement("Forging panel", d.forgingPlacement,
                    () -> style.forgingPlacement, v -> style.forgingPlacement = v))
                .option(toggle("Tick and cross marks", "Show ✔ or ✖ in front of each row.", d.showMarks,
                    () -> style.showMarks, v -> style.showMarks = v))
                .option(toggle("Row boxes", "Draw a background and coloured border behind each row.", d.showRowBoxes,
                    () -> style.showRowBoxes, v -> style.showRowBoxes = v))
                .option(toggle("Tree lines", "Draw lines joining ingredients to their recipe.", d.showTreeLines,
                    () -> style.showTreeLines, v -> style.showTreeLines = v))
                .build())
            .group(OptionGroup.createBuilder()
                .name(Component.literal("Spacing & sizes"))
                .option(slider("Row height", "Height of each row. Rows shrink if the HUD runs out of room.", 10, 24,
                    d.rowHeight, () -> style.rowHeight, v -> style.rowHeight = v))
                .option(slider("Row gap", "Space between rows.", 0, 6, d.rowGap, () -> style.rowGap, v -> style.rowGap = v))
                .option(slider("Indent", "How far each ingredient level is pushed right.", 4, 20, d.indent,
                    () -> style.indent, v -> style.indent = v))
                .option(slider("Padding", "Space between the panel edge and the rows.", 0, 16, d.padding,
                    () -> style.padding, v -> style.padding = v))
                .option(slider("Panel border", "Thickness of the panel border (0 = none).", 0, 4, d.panelBorderWidth,
                    () -> style.panelBorderWidth, v -> style.panelBorderWidth = v))
                .option(slider("Row border", "Thickness of each row's coloured border (0 = none).", 0, 2, d.rowBorderWidth,
                    () -> style.rowBorderWidth, v -> style.rowBorderWidth = v))
                .option(slider("Panel corners", "Rounds the panel corners by this many pixels (0 = square).", 0, 6,
                    d.panelRadius, () -> style.panelRadius, v -> style.panelRadius = v))
                .option(slider("Row corners", "Rounds the corners of each row box by this many pixels (0 = square).", 0, 6,
                    d.rowRadius, () -> style.rowRadius, v -> style.rowRadius = v))
                .build())
            .group(OptionGroup.createBuilder()
                .name(Component.literal("Colours"))
                .option(colour("Panel background", d.panelBackground, () -> style.panelBackground, v -> style.panelBackground = v))
                .option(colour("Panel border", d.panelBorder, () -> style.panelBorder, v -> style.panelBorder = v))
                .option(colour("Title bar", d.headerBackground, () -> style.headerBackground, v -> style.headerBackground = v))
                .option(colour("Title text", d.titleText, () -> style.titleText, v -> style.titleText = v))
                .option(colour("Dividers", d.divider, () -> style.divider, v -> style.divider = v))
                .option(colour("Row background", d.rowBackground, () -> style.rowBackground, v -> style.rowBackground = v))
                .option(colour("Tree lines", d.treeLines, () -> style.treeLines, v -> style.treeLines = v))
                .option(colour("Recipe names", d.rootText, () -> style.rootText, v -> style.rootText = v))
                .option(colour("Item names (done)", d.itemText, () -> style.itemText, v -> style.itemText = v))
                .option(colour("Done", d.done, () -> style.done, v -> style.done = v))
                .option(colour("Partly gathered", d.partial, () -> style.partial, v -> style.partial = v))
                .option(colour("Missing", d.missing, () -> style.missing, v -> style.missing = v))
                .option(colour("Can craft", d.craftable, () -> style.craftable, v -> style.craftable = v))
                .option(colour("Craftable header", d.sectionHeader, () -> style.sectionHeader, v -> style.sectionHeader = v))
                .option(colour("Craftable text", d.sectionText, () -> style.sectionText, v -> style.sectionText = v))
                .option(colour("Forging header", d.forgingHeader, () -> style.forgingHeader, v -> style.forgingHeader = v))
                .option(colour("Forging text", d.forgingText, () -> style.forgingText, v -> style.forgingText = v))
                .build())
            .build();
    }

    /** Remembered between screen rebuilds (each preset action reopens the screen). */
    private static String chosenPreset;
    private static String presetName = "My preset";

    /**
     * Presets group: load / save / delete presets and copy / paste share codes. Every action works on the saved
     * look and takes effect right away, then reopens the screen so the other options show the new values.
     */
    private static OptionGroup presets(Screen parent) {
        List<String> names = HudPresets.list();
        if (chosenPreset == null || !names.contains(chosenPreset)) chosenPreset = names.get(0);
        Option<String> chosen = Option.<String>createBuilder()
            .name(Component.literal("Preset"))
            .description(OptionDescription.of(Component.literal(
                "Your saved presets, then the built-in looks. \"" + HudPresets.BACKUP_NAME + "\" is your look "
                    + "from before the last Load, Paste or Reset.")))
            .binding(names.get(0), () -> chosenPreset, v -> chosenPreset = v)
            .controller(opt -> DropdownStringControllerBuilder.create(opt).values(names))
            .build();
        Option<String> name = Option.<String>createBuilder()
            .name(Component.literal("New preset name"))
            .description(OptionDescription.of(Component.literal("Name used by Save current as preset.")))
            .binding("My preset", () -> presetName, v -> presetName = v)
            .controller(StringControllerBuilder::create)
            .build();
        Runnable reopen = () -> Minecraft.getInstance().gui.setScreen(create(parent));
        String saveFirst = " Press Save first if you have unsaved Appearance changes.";
        return OptionGroup.createBuilder()
            .name(Component.literal("Presets"))
            .description(OptionDescription.of(Component.literal(
                "Presets hold the look plus each panel's size and scale. Panel positions are never changed.")))
            .option(chosen)
            .option(ButtonOption.createBuilder()
                .name(Component.literal("Load preset"))
                .description(OptionDescription.of(Component.literal(
                    "Switches to the chosen preset. Your current look is kept as \"" + HudPresets.BACKUP_NAME
                        + "\" first, so you can switch back.")))
                .action((screen, button) -> {
                    chosenPreset = chosen.pendingValue();
                    if (HudPresets.load(chosenPreset)) HudPresets.toast("Preset loaded", chosenPreset);
                    else HudPresets.toast("Preset not found", chosenPreset);
                    reopen.run();
                })
                .build())
            .option(ButtonOption.createBuilder()
                .name(Component.literal("Delete preset"))
                .description(OptionDescription.of(Component.literal(
                    "Deletes the chosen preset. Built-in presets can't be deleted.")))
                .action((screen, button) -> {
                    String target = chosen.pendingValue();
                    if (HudPresets.delete(target)) {
                        HudPresets.toast("Preset deleted", target);
                        chosenPreset = null;
                    } else {
                        HudPresets.toast("Can't delete", target);
                    }
                    reopen.run();
                })
                .build())
            .option(name)
            .option(ButtonOption.createBuilder()
                .name(Component.literal("Save current as preset"))
                .description(OptionDescription.of(Component.literal(
                    "Saves the current look and panel sizes under the name above (a number is added if the name "
                        + "is taken)." + saveFirst)))
                .action((screen, button) -> {
                    presetName = name.pendingValue();
                    chosenPreset = HudPresets.save(presetName);
                    HudPresets.toast("Preset saved", chosenPreset);
                    reopen.run();
                })
                .build())
            .option(ButtonOption.createBuilder()
                .name(Component.literal("Copy share code"))
                .description(OptionDescription.of(Component.literal(
                    "Copies the current look and panel sizes as text, to paste in chat or Discord." + saveFirst)))
                .action((screen, button) -> {
                    HudPresets.copyCode();
                    HudPresets.toast("Share code copied", "Paste it anywhere to share your look.");
                })
                .build())
            .option(ButtonOption.createBuilder()
                .name(Component.literal("Paste share code"))
                .description(OptionDescription.of(Component.literal(
                    "Reads a share code from the clipboard, saves it as a preset and switches to it. Your current "
                        + "look is kept as \"" + HudPresets.BACKUP_NAME + "\" first.")))
                .action((screen, button) -> {
                    String imported = HudPresets.pasteCode();
                    if (imported == null) {
                        HudPresets.toast("No share code", "The clipboard doesn't hold a " + InventoryReader.NAME + " code.");
                        return;
                    }
                    chosenPreset = imported;
                    HudPresets.toast("Share code loaded", imported);
                    reopen.run();
                })
                .build())
            .build();
    }

    private static Option<Float> size(String name, String description, float def, Supplier<Float> getter,
                                      Consumer<Float> setter) {
        return Option.<Float>createBuilder()
            .name(Component.literal(name))
            .description(OptionDescription.of(Component.literal(description)))
            .binding(def, getter, setter)
            .controller(opt -> FloatSliderControllerBuilder.create(opt).range(0.5f, 2.0f).step(0.05f))
            .build();
    }

    private static Option<HudStyle.Align> align(String name, HudStyle.Align def, Supplier<HudStyle.Align> getter,
                                                Consumer<HudStyle.Align> setter) {
        return Option.<HudStyle.Align>createBuilder()
            .name(Component.literal(name))
            .description(OptionDescription.of(Component.literal("Left, centre or right.")))
            .binding(def, getter, setter)
            .controller(opt -> EnumControllerBuilder.create(opt).enumClass(HudStyle.Align.class)
                .formatValue(v -> Component.literal(switch (v) {
                    case LEFT -> "Left";
                    case CENTRE -> "Centre";
                    case RIGHT -> "Right";
                })))
            .build();
    }

    private static Option<HudStyle.Placement> placement(String name, HudStyle.Placement def,
                                                        Supplier<HudStyle.Placement> getter,
                                                        Consumer<HudStyle.Placement> setter) {
        return Option.<HudStyle.Placement>createBuilder()
            .name(Component.literal(name))
            .description(OptionDescription.of(Component.literal(
                "Inside main panel: under the shopping list.\nOwn panel: a separate panel you can move and resize "
                    + "on its own in Move HUD.")))
            .binding(def, getter, setter)
            .controller(opt -> EnumControllerBuilder.create(opt).enumClass(HudStyle.Placement.class)
                .formatValue(v -> Component.literal(v == HudStyle.Placement.OWN_PANEL ? "Own panel" : "Inside main panel")))
            .build();
    }

    private static Option<Color> colour(String name, int def, IntSupplier getter, IntConsumer setter) {
        return Option.<Color>createBuilder()
            .name(Component.literal(name))
            .description(OptionDescription.of(Component.literal("Colour and transparency of: " + name.toLowerCase(Locale.ROOT) + ".")))
            .binding(new Color(def, true), () -> new Color(getter.getAsInt(), true), c -> setter.accept(c.getRGB()))
            .controller(opt -> ColorControllerBuilder.create(opt).allowAlpha(true))
            .build();
    }

    private static Option<Integer> slider(String name, String description, int min, int max, int def,
                                          Supplier<Integer> getter, Consumer<Integer> setter) {
        return Option.<Integer>createBuilder()
            .name(Component.literal(name))
            .description(OptionDescription.of(Component.literal(description)))
            .binding(def, getter, setter)
            .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(min, max).step(1))
            .build();
    }

    private static Option<Boolean> toggle(String name, String description, Supplier<Boolean> getter, Consumer<Boolean> setter) {
        return toggle(name, description, true, getter, setter);
    }

    private static Option<Boolean> toggle(String name, String description, boolean def, Supplier<Boolean> getter,
                                          Consumer<Boolean> setter) {
        return Option.<Boolean>createBuilder()
            .name(Component.literal(name))
            .description(OptionDescription.of(Component.literal(description)))
            .binding(def, getter, setter)
            .controller(opt -> BooleanControllerBuilder.create(opt).onOffFormatter().coloured(true))
            .build();
    }
}
