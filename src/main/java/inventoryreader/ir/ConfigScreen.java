package inventoryreader.ir;

import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionEventListener;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.ColorControllerBuilder;
import dev.isxander.yacl3.api.controller.DropdownStringControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.FloatSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.gui.image.ImageRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;

import java.awt.Color;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

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
                    false, widget::isEnabled, widget::setEnabled))
                .option(toggle("Total section",
                    "Show a Total at the top of the HUD with every raw material still needed across all recipes.",
                    true, widget::isShowTotal, widget::setShowTotal))
                .option(Option.<SandboxWidget.TotalMode>createBuilder()
                    .name(Component.literal("Total lists"))
                    .description(OptionDescription.of(Component.literal(
                        "Raw materials: everything down to the basic items (e.g. Titanium).\nRecipe ingredients: "
                            + "the pieces each recipe on your list takes directly (e.g. Refined Titanium).")))
                    .binding(SandboxWidget.TotalMode.RAW, widget::getTotalMode, widget::setTotalMode)
                    .controller(opt -> EnumControllerBuilder.create(opt).enumClass(SandboxWidget.TotalMode.class)
                        .formatValue(v -> Component.literal(
                            v == SandboxWidget.TotalMode.INGREDIENTS ? "Recipe ingredients" : "Raw materials")))
                    .build())
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
                    .text(Component.literal("Open"))
                    .description(OptionDescription.of(Component.literal(
                        "Drag a panel to move it, a corner to scale it, an edge to resize it. Also opened with B.")))
                    .action((screen, button) -> Minecraft.getInstance().gui.setScreen(new HudPositionScreen(screen)))
                    .build())
                .option(ButtonOption.createBuilder()
                    .name(Component.literal("Reset HUD position"))
                    .text(Component.literal("Reset"))
                    .description(OptionDescription.of(Component.literal("Put the HUD back in the top-left corner.")))
                    .action((screen, button) -> widget.resetPanelPositions())
                    .build())
                .build())
            .category(appearance(parent))
            .category(ConfigCategory.createBuilder()
                .name(Component.literal("Notifications"))
                .option(toggle("Pop-ups", "Pop-up when a recipe is ready to craft, and when you have made it.",
                    true, widget::isNotifications, widget::setNotifications))
                .option(toggle("Auto-remove",
                    "Take a recipe off the list once you have made the amount you asked for.",
                    true, widget::isAutoRemove, widget::setAutoRemove))
                .option(toggle("Sack reminder",
                    "Chat warning when your sacks haven't been opened for an hour. Between opens, sack counts "
                        + "follow the [Sacks] chat summaries and can drift.",
                    true, widget::isStaleSackWarning, widget::setStaleSackWarning))
                .build())
            .category(ConfigCategory.createBuilder()
                .name(Component.literal("Controls"))
                .option(ButtonOption.createBuilder()
                    .name(Component.literal("Key binds..."))
                    .text(Component.literal("Open"))
                    .description(OptionDescription.of(Component.literal(
                        "Rebind keys. This mod's keys are under \"" + InventoryReader.NAME + "\".")))
                    .action((screen, button) -> Minecraft.getInstance().gui.setScreen(
                        new KeyBindsScreen(screen, Minecraft.getInstance().options)))
                    .build())
                .build())
            .category(ConfigCategory.createBuilder()
                .name(Component.literal("Advanced"))
                .option(Option.<Boolean>createBuilder()
                    .name(Component.literal("Debug logging"))
                    .description(OptionDescription.of(Component.literal(
                        "Write what the mod reads (sack messages, Forge and HOTM menus, recipe downloads) to "
                            + "latest.log, to help track down a problem. Warnings and errors are always logged.")))
                    .binding(false, widget::isDebugLogging, widget::setDebugLogging)
                    .controller(opt -> BooleanControllerBuilder.create(opt).onOffFormatter().coloured(true))
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
        // The preview draws a copy that follows every unsaved change; the real look only changes on Save.
        HudStyle draft = HudStyle.copy();
        previewDraft = draft;
        previewImage = new ImageRenderer() {
            @Override
            public int render(GuiGraphicsExtractor context, int x, int y, int width, float delta) {
                return SandboxWidget.getInstance().renderPreview(context, x, y, width, draft);
            }

            @Override
            public void close() {
            }
        };
        try {
            return buildAppearance(parent, style, d, fonts);
        } finally {
            previewDraft = null;
            previewImage = null;
        }
    }

    private static ConfigCategory buildAppearance(Screen parent, HudStyle style, HudStyle d, List<String> fonts) {
        return ConfigCategory.createBuilder()
            .name(Component.literal("Appearance"))
            .option(ButtonOption.createBuilder()
                .name(Component.literal("Preview (Move HUD...)"))
                .text(Component.literal("Open"))
                .description(describe("Shows the HUD with the saved look in Move HUD. Press Save first to see "
                    + "changes there; the preview above already shows them."))
                .action((screen, button) -> Minecraft.getInstance().gui.setScreen(new HudPositionScreen(screen)))
                .build())
            .option(ButtonOption.createBuilder()
                .name(Component.literal("Reset look to defaults"))
                .text(Component.literal("Reset"))
                .description(describe(
                    "Puts every Appearance setting back to the original look, right away. Your current look is "
                        + "kept as the preset \"" + HudPresets.BACKUP_NAME + "\"."))
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
                    d.hudScale, s -> s.hudScale, (s, v) -> s.hudScale = v))
                .option(styled(Option.<HudStyle.Sizing>createBuilder()
                    .name(Component.literal("HUD sizing"))
                    .description(describe(
                        "Window size: grows and shrinks with the game window (HUD scale 100% is the original "
                            + "size at 1080p).\nMinecraft GUI Scale: follows GUI Scale in steps, like vanilla "
                            + "menus.\nFixed pixels: the same size on screen whatever the window or GUI Scale."))
                    .controller(opt -> EnumControllerBuilder.create(opt).enumClass(HudStyle.Sizing.class)
                        .formatValue(v -> Component.literal(switch (v) {
                            case WINDOW -> "Window size";
                            case GUI_SCALE -> "Minecraft GUI Scale";
                            case FIXED -> "Fixed pixels";
                        }))),
                    d.sizing, s -> s.sizing, (s, v) -> s.sizing = v))
                .build())
            .group(OptionGroup.createBuilder()
                .name(Component.literal("Text"))
                .option(styled(Option.<String>createBuilder()
                    .name(Component.literal("Font"))
                    .description(describe(
                        "Fonts from Minecraft and your enabled resource packs. If a pack's font is removed, the "
                            + "HUD falls back to the default font."))
                    .controller(opt -> DropdownStringControllerBuilder.create(opt).values(fonts)),
                    d.font, s -> s.font, (s, v) -> s.font = v))
                .option(size("Title size", "Size of the panel titles.", d.titleScale,
                    s -> s.titleScale, (s, v) -> s.titleScale = v))
                .option(align("Title alignment", d.titleAlign, s -> s.titleAlign, (s, v) -> s.titleAlign = v))
                .option(size("Row text size", "Size of the recipe rows. Rows stay left-aligned so the tree lines up.",
                    d.textScale, s -> s.textScale, (s, v) -> s.textScale = v))
                .option(size("Craftable text size", "Size of the Craftable section.", d.craftableScale,
                    s -> s.craftableScale, (s, v) -> s.craftableScale = v))
                .option(align("Craftable alignment", d.craftableAlign, s -> s.craftableAlign, (s, v) -> s.craftableAlign = v))
                .option(size("Forging text size", "Size of the Forging section.", d.forgingScale,
                    s -> s.forgingScale, (s, v) -> s.forgingScale = v))
                .option(align("Forging alignment", d.forgingAlign, s -> s.forgingAlign, (s, v) -> s.forgingAlign = v))
                .option(toggle("Text shadow", "Draw a shadow under HUD text.", d.textShadow,
                    s -> s.textShadow, (s, v) -> s.textShadow = v))
                .option(toggle("Bold recipe names", "Show the recipes on your list (top rows) in bold.", d.boldRootNames,
                    s -> s.boldRootNames, (s, v) -> s.boldRootNames = v))
                .build())
            .group(OptionGroup.createBuilder()
                .name(Component.literal("Parts"))
                .option(styled(Option.<HudStyle.AmountFormat>createBuilder()
                    .name(Component.literal("Amount format"))
                    .description(describe(
                        "Remaining: what is still left to get (3×).\nHave / need: what you hold of what is needed "
                            + "(83/5,120).\nRequired: the full amount the recipes need (6×).\n\nColours: red = none "
                            + "yet, orange = some, yellow = can craft from what you have, blue = cooking in the Forge, green = done."))
                    .controller(opt -> EnumControllerBuilder.create(opt).enumClass(HudStyle.AmountFormat.class)
                        .formatValue(v -> Component.literal(switch (v) {
                            case REMAINING -> "Remaining";
                            case HAVE_NEED -> "Have / need";
                            case REQUIRED -> "Required";
                        }))),
                    d.amountFormat, s -> s.amountFormat, (s, v) -> s.amountFormat = v))
                .option(toggle("Short numbers", "Show big amounts as 5.1k, 512k, 500m or 1.5b instead of the full "
                    + "number (rounded down).", d.shortNumbers,
                    s -> s.shortNumbers, (s, v) -> s.shortNumbers = v))
                .option(toggle("Craftable section", "Show what you can craft right now.", d.showCraftable,
                    s -> s.showCraftable, (s, v) -> s.showCraftable = v))
                .option(placement("Craftable panel", d.craftablePlacement,
                    s -> s.craftablePlacement, (s, v) -> s.craftablePlacement = v))
                .option(toggle("Forging section", "Show what is cooking in your Forge that the list needs.", d.showForging,
                    s -> s.showForging, (s, v) -> s.showForging = v))
                .option(placement("Forging panel", d.forgingPlacement,
                    s -> s.forgingPlacement, (s, v) -> s.forgingPlacement = v))
                .option(styled(Option.<HudStyle.ForgeTimes>createBuilder()
                    .name(Component.literal("Forge times"))
                    .description(describe(
                        "Shows how long the Forge still needs after a row's name, e.g. [25hrs]: every forge craft "
                            + "still to make for that row, one after another (time × how many, plus its "
                            + "ingredients). This is the worst case: forging several at once finishes sooner.\n"
                            + "Items cooking now add the time they have left. Crafts not started yet get Quick Forge "
                            + "(open Heart of the Mountain once) and Cole's Molten Forge while he is mayor or "
                            + "minister. Long names are cut short so the time always shows."))
                    .controller(opt -> EnumControllerBuilder.create(opt).enumClass(HudStyle.ForgeTimes.class)
                        .formatValue(v -> Component.literal(switch (v) {
                            case OFF -> "Off";
                            case TOP_LEVEL -> "Top level only";
                            case ALL -> "Every row";
                        }))),
                    d.forgeTimes, s -> s.forgeTimes, (s, v) -> s.forgeTimes = v))
                .option(toggle("Tick and cross marks", "Show ✔ or ✖ in front of each row.", d.showMarks,
                    s -> s.showMarks, (s, v) -> s.showMarks = v))
                .option(toggle("Row boxes", "Draw a background and coloured border behind each row.", d.showRowBoxes,
                    s -> s.showRowBoxes, (s, v) -> s.showRowBoxes = v))
                .option(toggle("Tree lines", "Draw lines joining ingredients to their recipe.", d.showTreeLines,
                    s -> s.showTreeLines, (s, v) -> s.showTreeLines = v))
                .build())
            .group(OptionGroup.createBuilder()
                .name(Component.literal("Spacing & sizes"))
                .option(slider("Row height", "Height of each row. Rows shrink if the HUD runs out of room.", 10, 24,
                    d.rowHeight, s -> s.rowHeight, (s, v) -> s.rowHeight = v))
                .option(slider("Row gap", "Space between rows.", 0, 6, d.rowGap, s -> s.rowGap, (s, v) -> s.rowGap = v))
                .option(slider("Indent", "How far each ingredient level is pushed right.", 4, 20, d.indent,
                    s -> s.indent, (s, v) -> s.indent = v))
                .option(slider("Padding", "Space between the panel edge and the rows.", 0, 16, d.padding,
                    s -> s.padding, (s, v) -> s.padding = v))
                .option(slider("Panel border", "Thickness of the panel border (0 = none).", 0, 4, d.panelBorderWidth,
                    s -> s.panelBorderWidth, (s, v) -> s.panelBorderWidth = v))
                .option(slider("Row border", "Thickness of each row's coloured border (0 = none).", 0, 2, d.rowBorderWidth,
                    s -> s.rowBorderWidth, (s, v) -> s.rowBorderWidth = v))
                .option(slider("Panel corners", "Rounds the panel corners by this many pixels (0 = square).", 0, 6,
                    d.panelRadius, s -> s.panelRadius, (s, v) -> s.panelRadius = v))
                .option(slider("Row corners", "Rounds the corners of each row box by this many pixels (0 = square).", 0, 6,
                    d.rowRadius, s -> s.rowRadius, (s, v) -> s.rowRadius = v))
                .build())
            .group(OptionGroup.createBuilder()
                .name(Component.literal("Tracking colours"))
                .description(describe("Colours that show how far along each item is: amounts, row borders and "
                    + "item names."))
                .option(colour("Done", d.done, s -> s.done, (s, v) -> s.done = v))
                .option(colour("Partly gathered", d.partial, s -> s.partial, (s, v) -> s.partial = v))
                .option(colour("Missing", d.missing, s -> s.missing, (s, v) -> s.missing = v))
                .option(colour("Can craft", d.craftable, s -> s.craftable, (s, v) -> s.craftable = v))
                .option(colour("Cooking", d.cooking, s -> s.cooking, (s, v) -> s.cooking = v))
                .build())
            .group(OptionGroup.createBuilder()
                .name(Component.literal("Panel colours"))
                .description(describe("Colours of the panels themselves: background, borders, titles, text and the "
                    + "Craftable / Forging sections."))
                .option(colour("Panel background", d.panelBackground, s -> s.panelBackground, (s, v) -> s.panelBackground = v))
                .option(colour("Panel border", d.panelBorder, s -> s.panelBorder, (s, v) -> s.panelBorder = v))
                .option(colour("Title bar", d.headerBackground, s -> s.headerBackground, (s, v) -> s.headerBackground = v))
                .option(colour("Title text", d.titleText, s -> s.titleText, (s, v) -> s.titleText = v))
                .option(colour("Dividers", d.divider, s -> s.divider, (s, v) -> s.divider = v))
                .option(colour("Row background", d.rowBackground, s -> s.rowBackground, (s, v) -> s.rowBackground = v))
                .option(colour("Tree lines", d.treeLines, s -> s.treeLines, (s, v) -> s.treeLines = v))
                .option(colour("Recipe names", d.rootText, s -> s.rootText, (s, v) -> s.rootText = v))
                .option(colour("Item names (done)", d.itemText, s -> s.itemText, (s, v) -> s.itemText = v))
                .option(colour("Craftable header", d.sectionHeader, s -> s.sectionHeader, (s, v) -> s.sectionHeader = v))
                .option(colour("Craftable text", d.sectionText, s -> s.sectionText, (s, v) -> s.sectionText = v))
                .option(colour("Forging header", d.forgingHeader, s -> s.forgingHeader, (s, v) -> s.forgingHeader = v))
                .option(colour("Forging text", d.forgingText, s -> s.forgingText, (s, v) -> s.forgingText = v))
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
            .description(describe(
                "Your saved presets, then the built-in looks. \"" + HudPresets.BACKUP_NAME + "\" is your look "
                    + "from before the last Load, Paste or Reset."))
            .binding(names.get(0), () -> chosenPreset, v -> chosenPreset = v)
            .controller(opt -> DropdownStringControllerBuilder.create(opt).values(names))
            .build();
        Option<String> name = Option.<String>createBuilder()
            .name(Component.literal("New preset name"))
            .description(describe(
"Name used by Save current as preset."))
            .binding("My preset", () -> presetName, v -> presetName = v)
            .controller(StringControllerBuilder::create)
            .build();
        Runnable reopen = () -> Minecraft.getInstance().gui.setScreen(create(parent));
        String saveFirst = " Press Save first if you have unsaved Appearance changes.";
        return OptionGroup.createBuilder()
            .name(Component.literal("Presets"))
            .description(describe(
                "Presets hold the look plus each panel's size and scale. Panel positions are never changed."))
            .option(chosen)
            .option(ButtonOption.createBuilder()
                .name(Component.literal("Load preset"))
                .text(Component.literal("Load"))
                .description(describe(
                    "Switches to the chosen preset. Your current look is kept as \"" + HudPresets.BACKUP_NAME
                        + "\" first, so you can switch back."))
                .action((screen, button) -> {
                    chosenPreset = chosen.pendingValue();
                    if (HudPresets.load(chosenPreset)) HudPresets.toast("Preset loaded", chosenPreset);
                    else HudPresets.toast("Preset not found", chosenPreset);
                    reopen.run();
                })
                .build())
            .option(ButtonOption.createBuilder()
                .name(Component.literal("Delete preset"))
                .text(Component.literal("Delete"))
                .description(describe(
                    "Deletes the chosen preset. Built-in presets can't be deleted."))
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
                .text(Component.literal("Save"))
                .description(describe(
                    "Saves the current look and panel sizes under the name above (a number is added if the name "
                        + "is taken)." + saveFirst))
                .action((screen, button) -> {
                    presetName = name.pendingValue();
                    try {
                        chosenPreset = HudPresets.save(presetName);
                    } catch (IOException e) {
                        HudPresets.toast("Preset not saved", "The file couldn't be written; see the log.");
                        return;
                    }
                    HudPresets.toast("Preset saved", chosenPreset);
                    reopen.run();
                })
                .build())
            .option(ButtonOption.createBuilder()
                .name(Component.literal("Copy share code"))
                .text(Component.literal("Copy"))
                .description(describe(
                    "Copies the current look and panel sizes as text, to paste in chat or Discord." + saveFirst))
                .action((screen, button) -> {
                    HudPresets.copyCode();
                    HudPresets.toast("Share code copied", "Paste it anywhere to share your look.");
                })
                .build())
            .option(ButtonOption.createBuilder()
                .name(Component.literal("Paste share code"))
                .text(Component.literal("Paste"))
                .description(describe(
                    "Reads a share code from the clipboard, saves it as a preset and switches to it. Your current "
                        + "look is kept as \"" + HudPresets.BACKUP_NAME + "\" first."))
                .action((screen, button) -> {
                    String imported;
                    try {
                        imported = HudPresets.pasteCode();
                    } catch (IOException e) {
                        HudPresets.toast("Preset not saved", "The file couldn't be written; see the log.");
                        return;
                    }
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

    /** While Appearance is being built: the copy the preview draws, and the preview itself. */
    private static HudStyle previewDraft;
    private static ImageRenderer previewImage;

    /** Description text, with the live preview above it while building Appearance. */
    private static OptionDescription describe(String text) {
        OptionDescription.Builder builder = OptionDescription.createBuilder().text(Component.literal(text));
        if (previewImage != null) builder.customImage(previewImage);
        return builder.build();
    }

    /**
     * Finishes an Appearance option: the binding reads and writes the saved look (applied on Save), and every
     * unsaved change is copied to the preview's draft straight away.
     */
    private static <T> Option<T> styled(Option.Builder<T> builder, T def, Function<HudStyle, T> get,
                                        BiConsumer<HudStyle, T> set) {
        HudStyle style = HudStyle.get();
        HudStyle draft = previewDraft;
        builder.binding(def, () -> get.apply(style), v -> set.accept(style, v));
        if (draft != null) {
            builder.addListener((option, event) -> {
                if (event == OptionEventListener.Event.STATE_CHANGE) set.accept(draft, option.pendingValue());
            });
        }
        return builder.build();
    }

    private static Option<Float> size(String name, String description, float def, Function<HudStyle, Float> get,
                                      BiConsumer<HudStyle, Float> set) {
        return styled(Option.<Float>createBuilder()
            .name(Component.literal(name))
            .description(describe(description))
            .controller(opt -> FloatSliderControllerBuilder.create(opt).range(0.5f, 2.0f).step(0.05f)),
            def, get, set);
    }

    private static Option<HudStyle.Align> align(String name, HudStyle.Align def, Function<HudStyle, HudStyle.Align> get,
                                                BiConsumer<HudStyle, HudStyle.Align> set) {
        return styled(Option.<HudStyle.Align>createBuilder()
            .name(Component.literal(name))
            .description(describe("Left, centre or right."))
            .controller(opt -> EnumControllerBuilder.create(opt).enumClass(HudStyle.Align.class)
                .formatValue(v -> Component.literal(switch (v) {
                    case LEFT -> "Left";
                    case CENTRE -> "Centre";
                    case RIGHT -> "Right";
                }))),
            def, get, set);
    }

    private static Option<HudStyle.Placement> placement(String name, HudStyle.Placement def,
                                                        Function<HudStyle, HudStyle.Placement> get,
                                                        BiConsumer<HudStyle, HudStyle.Placement> set) {
        return styled(Option.<HudStyle.Placement>createBuilder()
            .name(Component.literal(name))
            .description(describe(
                "Inside main panel: under the shopping list.\nOwn panel: a separate panel you can move and resize "
                    + "on its own in Move HUD."))
            .controller(opt -> EnumControllerBuilder.create(opt).enumClass(HudStyle.Placement.class)
                .formatValue(v -> Component.literal(v == HudStyle.Placement.OWN_PANEL ? "Own panel" : "Inside main panel"))),
            def, get, set);
    }

    private static Option<Color> colour(String name, int def, ToIntFunction<HudStyle> get, ObjIntConsumer<HudStyle> set) {
        return styled(Option.<Color>createBuilder()
            .name(Component.literal(name))
            .description(describe("Colour and transparency of: " + name.toLowerCase(Locale.ROOT) + "."))
            .controller(opt -> ColorControllerBuilder.create(opt).allowAlpha(true)),
            new Color(def, true), s -> new Color(get.applyAsInt(s), true), (s, c) -> set.accept(s, c.getRGB()));
    }

    private static Option<Integer> slider(String name, String description, int min, int max, int def,
                                          Function<HudStyle, Integer> get, BiConsumer<HudStyle, Integer> set) {
        return styled(Option.<Integer>createBuilder()
            .name(Component.literal(name))
            .description(describe(description))
            .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(min, max).step(1)),
            def, get, set);
    }

    private static Option<Boolean> toggle(String name, String description, boolean def, Function<HudStyle, Boolean> get,
                                          BiConsumer<HudStyle, Boolean> set) {
        return styled(Option.<Boolean>createBuilder()
            .name(Component.literal(name))
            .description(describe(description))
            .controller(opt -> BooleanControllerBuilder.create(opt).onOffFormatter().coloured(true)),
            def, get, set);
    }

    /** A widget setting; {@code def} is what the reset button sets, the same as the widget's own default. */
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
