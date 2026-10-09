package inventoryreader.ir;

import java.util.HashMap;
import java.util.Map;

/** Small helpers for item counts. */
public final class Counts {
    private Counts() {}

    /** Per item, how much {@code current} differs from {@code previous}; items that are gone count negative. */
    public static Map<String, Integer> diff(Map<String, Integer> previous, Map<String, Integer> current) {
        Map<String, Integer> changes = new HashMap<>();
        current.forEach((name, count) -> {
            int delta = count - previous.getOrDefault(name, 0);
            if (delta != 0) changes.put(name, delta);
        });
        previous.forEach((name, count) -> {
            if (!current.containsKey(name) && count != 0) changes.put(name, -count);
        });
        return changes;
    }

    /** "1,234" -> 1234; null when it isn't a number. */
    public static Integer parse(String text) {
        try {
            return Integer.parseInt(text.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
