package inventoryreader.ir;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Item display names differ between sources: in-game names carry stat symbols ("☘ Fine Jade Gemstone"),
 * star/upgrade glyphs and colour codes, while sack lore and chat use the plain name. Matching goes
 * through {@link #normalize} so every spelling resolves to the same resource entry.
 */
public final class ItemNames {
    private static final Pattern FORMATTING = Pattern.compile("§.");
    private static final Pattern UPGRADE_GLYPHS = Pattern.compile("[✪➊➋➌➍➎]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ItemNames() {}

    /** Display form: formatting codes and upgrade glyphs removed, symbol prefix kept. */
    public static String clean(String raw) {
        if (raw == null) return "";
        String s = FORMATTING.matcher(raw).replaceAll("");
        s = UPGRADE_GLYPHS.matcher(s).replaceAll("");
        return WHITESPACE.matcher(s).replaceAll(" ").trim();
    }

    /** Lookup key: {@link #clean} with symbol-only words dropped and case folded. */
    public static String normalize(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String word : clean(raw).split(" ")) {
            if (word.isEmpty() || isSymbolToken(word)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(word);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** True for names that are only digits, which show up when malformed lines get parsed as items. */
    public static boolean isJunk(String name) {
        if (name == null) return true;
        String t = name.trim();
        return t.isEmpty() || t.chars().allMatch(Character::isDigit);
    }

    private static boolean isSymbolToken(String token) {
        return token.codePoints().noneMatch(Character::isLetterOrDigit);
    }
}
