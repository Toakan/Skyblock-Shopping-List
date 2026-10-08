package inventoryreader.ir;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** UTF-8 JSON file helpers. Reads never throw; writes go through a temp file and a move. */
public final class JsonFiles {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private JsonFiles() {}

    /** Returns the parsed file, or null when it is missing, empty or corrupt. */
    public static <T> T read(File file, Type type) {
        if (file == null || !file.isFile() || file.length() == 0) return null;
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, type);
        } catch (IOException | JsonParseException e) {
            InventoryReader.LOGGER.warn("Could not read {}: {}", file.getName(), e.toString());
            return null;
        }
    }

    public static boolean write(File file, Object value) {
        Path target = file.toPath();
        Path tmp = target.resolveSibling(file.getName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(value, writer);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            InventoryReader.LOGGER.error("Could not write {}", file.getName(), e);
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            return false;
        }
    }
}
