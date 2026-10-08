package inventoryreader.ir;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * How the shopping-list HUD looks: colours, sizes, text and which parts are drawn. Edited in Settings >
 * Appearance and saved to hud_style.json. Defaults match the original look.
 */
public final class HudStyle {
    public enum AmountFormat { REMAINING, HAVE_NEED, REQUIRED }

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
    public int sectionHeader = 0xFFFFFF55;
    public int sectionText = 0xFFFF9D00;

    // Sizes (GUI pixels).
    public int rowHeight = 16;
    public int indent = 10;
    public int padding = 6;
    public int rowGap = 0;
    public int panelBorderWidth = 2;
    public int rowBorderWidth = 1;

    // Text.
    public String font = DEFAULT_FONT;
    public float textScale = 1.0f;
    public boolean textShadow = false;
    public boolean boldRootNames = true;

    // Parts.
    public boolean showTreeLines = true;
    public boolean showRowBoxes = true;
    public boolean showMarks = false;
    public AmountFormat amountFormat = AmountFormat.REMAINING;

    private static volatile HudStyle current;
    /** Font id -> description, cached so text drawing doesn't parse the id every frame. */
    private transient FontDescription fontDescription;

    /** The active style, loaded from disk on first use. */
    public static HudStyle get() {
        HudStyle style = current;
        if (style == null) {
            style = JsonFiles.read(FilePathManager.HUD_STYLE_JSON, HudStyle.class);
            if (style == null) style = new HudStyle();
            style.clamp();
            current = style;
        }
        return style;
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
        textScale = Math.max(0.5f, Math.min(1.5f, textScale));
        if (font == null || Identifier.tryParse(font) == null) font = DEFAULT_FONT;
        if (amountFormat == null) amountFormat = AmountFormat.REMAINING;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Text in the chosen font and colour. Unknown font ids fall back to Minecraft's default font. */
    public Component text(String text, int color, boolean bold) {
        return Component.literal(text).setStyle(Style.EMPTY.withColor(color & 0xFFFFFF).withBold(bold).withFont(fontDescription()));
    }

    private FontDescription fontDescription() {
        FontDescription description = fontDescription;
        if (description == null) {
            Identifier id = Identifier.tryParse(font);
            description = id == null ? FontDescription.DEFAULT : new FontDescription.Resource(id);
            fontDescription = description;
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
