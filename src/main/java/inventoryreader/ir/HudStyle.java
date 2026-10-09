package inventoryreader.ir;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * How the shopping-list HUD looks: colours, sizes, text and which parts are drawn. Edited in Settings >
 * Appearance and saved to hud_style.json. Defaults match the original look.
 */
public final class HudStyle {
    public enum AmountFormat { REMAINING, HAVE_NEED, REQUIRED }
    public enum Align { LEFT, CENTRE, RIGHT }
    /** Where the Craftable / Forging sections go. */
    public enum Placement { MAIN_PANEL, OWN_PANEL }

    public static final String DEFAULT_FONT = "minecraft:default";

    // Colours (ARGB).
    public int panelBackground = 0x99271910;
    public int panelBorder = 0xFFDAA520;
    public int headerBackground = 0xCC2C4A1B;
    public int titleText = 0xFFFFB728;
    public int divider = 0x99608C35;
    public int rowBackground = 0x99271910;
    public int treeLines = 0xFF777777;
    public int itemText = 0xFFFFFFFF;
    public int rootText = 0xFFFFB728;
    public int done = 0xFF6EFF6E;
    public int partial = 0xFFFFA040;
    public int missing = 0xFFFF6B6B;
    public int craftable = 0xFFFFE45C;
    /** Held, but some of it is still cooking in the Forge. */
    public int cooking = 0xFF5CC8FF;
    public int sectionHeader = 0xFFFFFF55;
    public int sectionText = 0xFFFF9D00;
    public int forgingHeader = 0xFFFFFF55;
    public int forgingText = 0xFFFF9D00;

    /** Which rows show the forge time still ahead, e.g. [25hrs]. */
    public enum ForgeTimes { OFF, TOP_LEVEL, ALL }
    /** How the HUD size reacts to the window: with its height, with GUI Scale, or not at all. */
    public enum Sizing { WINDOW, GUI_SCALE, FIXED }

    // Size: percent of the original size (as drawn in a 1080p window).
    public int hudScale = 100;
    public Sizing sizing = Sizing.WINDOW;
    /** Read only: the Follow GUI Scale toggle from before HUD sizing, carried over in {@link #clamp()}. */
    public Boolean followGuiScale;

    // Sizes (GUI pixels).
    public int rowHeight = 16;
    public int indent = 10;
    public int padding = 6;
    public int rowGap = 0;
    public int panelBorderWidth = 2;
    public int rowBorderWidth = 1;
    /** Corner rounding in pixels (0 = square). */
    public int panelRadius = 0;
    public int rowRadius = 0;

    // Text.
    public String font = DEFAULT_FONT;
    /** Row (recipe tree) text size. */
    public float textScale = 1.0f;
    public float titleScale = 1.0f;
    public float craftableScale = 1.0f;
    public float forgingScale = 1.0f;
    public Align titleAlign = Align.CENTRE;
    public Align craftableAlign = Align.CENTRE;
    public Align forgingAlign = Align.CENTRE;
    public boolean textShadow = false;
    public boolean boldRootNames = true;

    // Parts.
    public boolean showTreeLines = true;
    public boolean showRowBoxes = true;
    public boolean showMarks = false;
    public AmountFormat amountFormat = AmountFormat.REMAINING;
    /** Row amounts as 1.2k / 500m / 1.5b instead of the full number. */
    public boolean shortNumbers = true;
    public boolean showCraftable = true;
    public boolean showForging = true;
    /** Coins to buy the list's raw materials at NPCs and what NPCs pay for them, at the bottom of the main panel. */
    public boolean showNpcPrice = true;
    public ForgeTimes forgeTimes = ForgeTimes.ALL;
    public Placement craftablePlacement = Placement.MAIN_PANEL;
    public Placement forgingPlacement = Placement.MAIN_PANEL;

    private static volatile HudStyle current;
    /** Style drawn instead of the active one on this thread (the Appearance preview), or null. */
    private static final ThreadLocal<HudStyle> OVERRIDE = new ThreadLocal<>();
    /** Font id -> description, cached so text drawing doesn't parse the id every frame. */
    private transient FontDescription fontDescription;
    /** The font id {@link #fontDescription} was made from (the preview changes {@link #font} on the fly). */
    private transient String fontDescriptionId;

    /** The active style, loaded from disk on first use. */
    public static HudStyle get() {
        HudStyle override = OVERRIDE.get();
        if (override != null) return override;
        HudStyle style = current;
        if (style == null) {
            style = JsonFiles.read(FilePathManager.HUD_STYLE_JSON, HudStyle.class);
            if (style == null) style = new HudStyle();
            style.clamp();
            current = style;
        }
        return style;
    }

    /** Runs {@code action} with {@link #get()} returning {@code style} on this thread (for previews). */
    public static <T> T withOverride(HudStyle style, Supplier<T> action) {
        OVERRIDE.set(style);
        try {
            return action.get();
        } finally {
            OVERRIDE.remove();
        }
    }

    /** An independent copy of the active style. */
    public static HudStyle copy() {
        HudStyle copy = JsonFiles.GSON.fromJson(JsonFiles.GSON.toJson(get()), HudStyle.class);
        copy.clamp();
        return copy;
    }

    /** True once hud_style.json exists (used to migrate the old Show remaining setting only once). */
    public static boolean isSaved() {
        return FilePathManager.HUD_STYLE_JSON.exists();
    }

    /** Writes the active style to disk. */
    public static void save() {
        HudStyle style = get();
        style.clamp();
        style.fontDescription = null;
        JsonFiles.write(FilePathManager.HUD_STYLE_JSON, style);
    }

    /** Switches to {@code style} (from a preset) and saves. The player's own HUD sizing choice is kept. */
    public static void replace(HudStyle style) {
        style.sizing = get().sizing;
        style.fontDescription = null;
        style.clamp();
        current = style;
        save();
    }

    /** Puts every look setting back to its default and saves. */
    public static void reset() {
        current = new HudStyle();
        save();
    }

    /** Forgets the in-memory copy (after a reset deleted the file). */
    public static void clear() {
        current = null;
    }

    private void clamp() {
        rowHeight = clamp(rowHeight, 10, 24);
        indent = clamp(indent, 4, 20);
        padding = clamp(padding, 0, 16);
        rowGap = clamp(rowGap, 0, 6);
        panelBorderWidth = clamp(panelBorderWidth, 0, 4);
        rowBorderWidth = clamp(rowBorderWidth, 0, 2);
        panelRadius = clamp(panelRadius, 0, 6);
        rowRadius = clamp(rowRadius, 0, 6);
        textScale = clamp(textScale, 0.5f, 2.0f);
        titleScale = clamp(titleScale, 0.5f, 2.0f);
        craftableScale = clamp(craftableScale, 0.5f, 2.0f);
        forgingScale = clamp(forgingScale, 0.5f, 2.0f);
        hudScale = clamp(hudScale, 50, 300);
        if (titleAlign == null) titleAlign = Align.CENTRE;
        if (craftableAlign == null) craftableAlign = Align.CENTRE;
        if (forgingAlign == null) forgingAlign = Align.CENTRE;
        if (craftablePlacement == null) craftablePlacement = Placement.MAIN_PANEL;
        if (forgingPlacement == null) forgingPlacement = Placement.MAIN_PANEL;
        if (font == null || Identifier.tryParse(font) == null) font = DEFAULT_FONT;
        if (amountFormat == null) amountFormat = AmountFormat.REMAINING;
        if (forgeTimes == null) forgeTimes = ForgeTimes.ALL;
        // Files from before HUD sizing: Follow GUI Scale ON keeps following it, OFF gets the new default.
        if (followGuiScale != null) {
            if (Boolean.TRUE.equals(followGuiScale)) sizing = Sizing.GUI_SCALE;
            followGuiScale = null;
        }
        if (sizing == null) sizing = Sizing.WINDOW;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Text in the chosen font and colour. Unknown font ids fall back to Minecraft's default font. */
    public Component text(String text, int color, boolean bold) {
        return Component.literal(text).setStyle(Style.EMPTY.withColor(color & 0xFFFFFF).withBold(bold).withFont(fontDescription()));
    }

    private FontDescription fontDescription() {
        FontDescription description = fontDescription;
        if (description == null || !font.equals(fontDescriptionId)) {
            Identifier id = Identifier.tryParse(font);
            description = id == null ? FontDescription.DEFAULT : new FontDescription.Resource(id);
            fontDescription = description;
            fontDescriptionId = font;
        }
        return description;
    }

    /** Every font the loaded resource packs provide (assets/<ns>/font/<name>.json), default first. */
    public static List<String> availableFonts() {
        List<String> fonts = new ArrayList<>();
        FileToIdConverter converter = FileToIdConverter.json("font");
        for (Identifier file : converter.listMatchingResources(Minecraft.getInstance().getResourceManager()).keySet()) {
            Identifier id = converter.fileToId(file);
            // "include/..." fonts are building blocks other fonts pull in, not fonts to pick.
            if (!id.getPath().startsWith("include/")) fonts.add(id.toString());
        }
        fonts.sort(null);
        fonts.remove(DEFAULT_FONT);
        fonts.add(0, DEFAULT_FONT);
        return fonts;
    }
}
