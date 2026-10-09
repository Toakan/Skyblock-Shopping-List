package inventoryreader.ir;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Saved HUD setups: the look ({@link HudStyle}) plus each panel's size and scale, never positions (those depend on
 * the player's screen). Player presets live in presets/ as one JSON file each; built-ins are made in code. Share
 * codes are a preset as gzipped, Base64 JSON behind {@link #CODE_PREFIX}, for pasting into chat or Discord.
 *
 * <p>Anything that replaces the current setup first saves it as {@link #BACKUP_NAME}, so it can be undone.
 */
public final class HudPresets {
    public static final String CODE_PREFIX = "SSL1:";
    public static final String BACKUP_NAME = "Previous (auto)";
    public static final String MIGRATED_NAME = "My look";
    private static final String BUILT_IN = "Built-in: ";
    private static final int NAME_LIMIT = 40;
    /** Share codes are a few KB; anything far bigger is refused before it is decoded or unpacked. */
    private static final int MAX_CODE_CHARS = 64 * 1024;
    private static final int MAX_JSON_BYTES = 256 * 1024;
    /** Compact JSON for share codes (the files use the pretty {@link JsonFiles#GSON}). */
    private static final Gson CODE_GSON = new Gson();
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(3000L);

    private HudPresets() {}

    /** One saved setup. Fields missing from older or hand-made files keep these defaults. */
    public static final class Preset {
        int version = 1;
        String name;
        HudStyle style;
        Layout layout;
    }

    public static final class Layout {
        Size main;
        Size craftable;
        Size forging;
    }

    public static final class Size {
        int width;
        int height;
        int scale;
    }

    /**
     * Runs once, the first time a version with presets starts: an existing customised setup is kept as
     * {@link #MIGRATED_NAME} before the player ever touches a preset.
     */
    public static void migrate() {
        File dir = FilePathManager.PRESETS_DIR;
        if (dir.exists()) return;
        boolean customised = HudStyle.isSaved() || FilePathManager.WIDGET_CONFIG_JSON.exists();
        if (!dir.mkdirs()) return;
        if (customised) write(capture(MIGRATED_NAME));
    }

    /** The current setup as a preset. */
    public static Preset capture(String name) {
        Preset preset = new Preset();
        preset.name = name;
        // Round-trip through JSON for an independent copy of the live style.
        preset.style = CODE_GSON.fromJson(CODE_GSON.toJson(HudStyle.get()), HudStyle.class);
        SandboxWidget widget = SandboxWidget.getInstance();
        preset.layout = new Layout();
        preset.layout.main = size(widget.getPanelRect(SandboxWidget.Panel.MAIN));
        preset.layout.craftable = size(widget.getPanelRect(SandboxWidget.Panel.CRAFTABLE));
        preset.layout.forging = size(widget.getPanelRect(SandboxWidget.Panel.FORGING));
        return preset;
    }

    private static Size size(SandboxWidget.PanelRect rect) {
        Size size = new Size();
        size.width = rect.width;
        size.height = rect.height;
        size.scale = rect.scalePercent();
        return size;
    }

    /** Saves the current setup as a backup, then switches to {@code preset}. Panels stay where they are. */
    public static void apply(Preset preset) {
        backup();
        HudStyle.replace(preset.style != null ? preset.style : new HudStyle());
        if (preset.layout != null) {
            applySize(SandboxWidget.Panel.MAIN, preset.layout.main);
            applySize(SandboxWidget.Panel.CRAFTABLE, preset.layout.craftable);
            applySize(SandboxWidget.Panel.FORGING, preset.layout.forging);
        }
    }

    private static void applySize(SandboxWidget.Panel panel, Size size) {
        if (size == null || size.width <= 0 || size.height <= 0) return;
        SandboxWidget widget = SandboxWidget.getInstance();
        SandboxWidget.PanelRect rect = widget.getPanelRect(panel);
        // setPanelRect clamps width, height and scale.
        widget.setPanelRect(panel, rect.x, rect.y, size.width, size.height, size.scale > 0 ? size.scale : 100);
    }

    /** Keeps the current setup as {@link #BACKUP_NAME}, replacing the previous backup. */
    public static void backup() {
        write(capture(BACKUP_NAME));
    }

    /** Player preset names (A-Z), kept until the preset folder changes; Settings asks on every rebuild. */
    private static List<String> playerNames;
    private static long playerNamesStamp;

    /** Player presets (A-Z), then the built-ins. Never empty. */
    public static synchronized List<String> list() {
        File[] files = FilePathManager.PRESETS_DIR.listFiles((dir, file) -> file.endsWith(".json"));
        // Folder time plus file count: changes when a preset is added, removed or renamed outside the game too.
        long stamp = FilePathManager.PRESETS_DIR.lastModified() * 31 + (files == null ? 0 : files.length);
        if (playerNames == null || stamp != playerNamesStamp) {
            List<String> names = new ArrayList<>(readAll().keySet());
            names.sort(String.CASE_INSENSITIVE_ORDER);
            playerNames = List.copyOf(names);
            playerNamesStamp = stamp;
        }
        List<String> names = new ArrayList<>(playerNames);
        names.addAll(builtIns().keySet());
        return names;
    }

    /** Forgets the cached names after this mod saved or deleted a preset. */
    private static synchronized void namesChanged() {
        playerNames = null;
    }

    public static boolean isBuiltIn(String name) {
        return name != null && builtIns().containsKey(name);
    }

    /** Saves the current setup under {@code name}, made unique; returns the name used. */
    public static String save(String name) {
        String unique = uniqueName(name, "Preset");
        write(capture(unique));
        return unique;
    }

    /** {@code name} cleaned, with " (2)", " (3)"... added when a preset already has it. */
    private static String uniqueName(String name, String fallback) {
        String clean = cleanName(name);
        if (clean.isEmpty()) clean = fallback;
        String unique = clean;
        List<String> taken = list();
        for (int n = 2; containsIgnoreCase(taken, unique) || fileFor(unique).exists(); n++) {
            unique = clean + " (" + n + ")";
        }
        return unique;
    }

    /** Loads a player or built-in preset; false when it no longer exists. */
    public static boolean load(String name) {
        Preset preset = builtIns().get(name);
        if (preset == null) preset = readAll().get(name);
        if (preset == null) return false;
        apply(preset);
        return true;
    }

    /** Deletes a player preset; built-ins can't be deleted. */
    public static boolean delete(String name) {
        if (name == null || isBuiltIn(name)) return false;
        File file = fileFor(name);
        boolean deleted = file.isFile() && file.delete();
        namesChanged();
        return deleted;
    }

    /** Copies the current setup to the clipboard as a share code. */
    public static void copyCode() {
        String json = CODE_GSON.toJson(capture("Shared look"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(
            CODE_PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray()));
    }

    /**
     * Reads a share code from the clipboard, saves it as a preset and applies it. Returns the saved name, or null
     * (with nothing changed) when the clipboard holds no valid code.
     */
    public static String pasteCode() {
        Preset preset = decode(Minecraft.getInstance().keyboardHandler.getClipboard());
        if (preset == null) return null;
        preset.name = uniqueName(preset.name, "Imported");
        write(preset);
        apply(preset);
        return preset.name;
    }

    static Preset decode(String code) {
        if (code == null) return null;
        code = code.trim();
        if (!code.startsWith(CODE_PREFIX) || code.length() - CODE_PREFIX.length() > MAX_CODE_CHARS) return null;
        try {
            byte[] packed = Base64.getDecoder().decode(code.substring(CODE_PREFIX.length()));
            String json;
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(packed))) {
                // One byte over the limit tells an oversized preset from one that just fits.
                byte[] raw = gzip.readNBytes(MAX_JSON_BYTES + 1);
                if (raw.length > MAX_JSON_BYTES) return null;
                json = new String(raw, StandardCharsets.UTF_8);
            }
            Preset preset = CODE_GSON.fromJson(json, Preset.class);
            if (preset == null || preset.style == null) return null;
            preset.name = cleanName(preset.name);
            return preset;
        } catch (IllegalArgumentException | IOException | JsonParseException e) {
            return null;
        }
    }

    public static void toast(String title, String message) {
        Minecraft client = Minecraft.getInstance();
        SystemToast.add(client.gui.toastManager(), TOAST,
            Component.literal(title).withStyle(ChatFormatting.GOLD), Component.literal(message));
    }

    private static Map<String, Preset> readAll() {
        Map<String, Preset> presets = new LinkedHashMap<>();
        File[] files = FilePathManager.PRESETS_DIR.listFiles((dir, file) -> file.endsWith(".json"));
        if (files == null) return presets;
        for (File file : files) {
            Preset preset = JsonFiles.read(file, Preset.class);
            if (preset == null) continue;
            String name = cleanName(preset.name);
            if (name.isEmpty()) name = file.getName().substring(0, file.getName().length() - 5);
            preset.name = name;
            presets.putIfAbsent(name, preset);
        }
        return presets;
    }

    private static void write(Preset preset) {
        JsonFiles.write(fileFor(preset.name), preset);
        namesChanged();
    }

    /** File name from the preset name: letters, digits, spaces and a few marks only, so names can't leave the folder. */
    private static File fileFor(String name) {
        String file = cleanName(name).replaceAll("[^A-Za-z0-9 _()+-]", "_").trim();
        if (file.isEmpty()) file = "preset";
        return new File(FilePathManager.PRESETS_DIR, file + ".json");
    }

    private static String cleanName(String name) {
        if (name == null) return "";
        String clean = name.replaceAll("\\p{Cntrl}", "").trim();
        if (clean.startsWith(BUILT_IN)) clean = clean.substring(BUILT_IN.length()).trim();
        return clean.length() > NAME_LIMIT ? clean.substring(0, NAME_LIMIT).trim() : clean;
    }

    private static boolean containsIgnoreCase(List<String> names, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String n : names) {
            if (n.toLowerCase(Locale.ROOT).equals(lower)) return true;
        }
        return false;
    }

    /** The looks that ship with the mod. Sizes are left out, so loading one keeps the player's panel sizes. */
    private static Map<String, Preset> builtIns() {
        Map<String, Preset> presets = new LinkedHashMap<>();

        presets.put(BUILT_IN + "Default", builtIn(BUILT_IN + "Default", new HudStyle()));

        HudStyle compact = new HudStyle();
        compact.rowHeight = 12;
        compact.padding = 3;
        compact.indent = 6;
        compact.textScale = 0.8f;
        compact.titleScale = 0.8f;
        compact.craftableScale = 0.8f;
        compact.forgingScale = 0.8f;
        compact.panelBorderWidth = 1;
        presets.put(BUILT_IN + "Compact", builtIn(BUILT_IN + "Compact", compact));

        HudStyle minimal = new HudStyle();
        minimal.panelBorderWidth = 0;
        minimal.showRowBoxes = false;
        minimal.showTreeLines = false;
        minimal.panelBackground = 0x66000000;
        minimal.headerBackground = 0x88000000;
        minimal.panelRadius = 3;
        presets.put(BUILT_IN + "Minimal", builtIn(BUILT_IN + "Minimal", minimal));
        return presets;
    }

    private static Preset builtIn(String name, HudStyle style) {
        Preset preset = new Preset();
        preset.name = name;
        preset.style = style;
        return preset;
    }
}
