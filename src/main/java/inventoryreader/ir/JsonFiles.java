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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * UTF-8 JSON file helpers. Reads never throw; writes go through a temp file and a move. Frequent saves use
 * {@link #writeAsync}, so the game thread never waits on the disk.
 */
public final class JsonFiles {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** The one thread that writes files, so two writers can never share a ".tmp" file. */
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "IR-Writer");
        t.setDaemon(true);
        return t;
    });
    /** Latest unsaved value per file; a newer save replaces one still waiting. */
    private static final Map<Path, Supplier<?>> PENDING = new HashMap<>();

    private JsonFiles() {}

    /**
     * Saves on the writer thread. {@code value} is called there, once per actual write, so it should return a
     * copy (or something nobody changes afterwards). Saves to the same file in quick succession become one.
     */
    public static void writeAsync(File file, Supplier<?> value) {
        Path path = file.toPath();
        boolean queued;
        synchronized (PENDING) {
            queued = PENDING.put(path, value) != null;
        }
        if (queued) return;
        WRITER.execute(() -> {
            Supplier<?> latest;
            synchronized (PENDING) {
                latest = PENDING.remove(path);
            }
            if (latest != null) write(file, latest.get());
        });
    }

    /** Waits (up to 5 s) until every save queued so far is on disk. */
    public static void flush() {
        try {
            WRITER.submit(() -> {}).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            InventoryReader.LOGGER.warn("Saving files took too long: {}", e.toString());
        }
    }

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
        return writeText(file, GSON.toJson(value));
    }

    /** Writes {@code text} as the whole file, through a temp file and a move. */
    public static boolean writeText(File file, String text) {
        Path target = file.toPath();
        Path tmp = target.resolveSibling(file.getName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                writer.write(text);
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
