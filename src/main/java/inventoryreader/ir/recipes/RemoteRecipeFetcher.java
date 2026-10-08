package inventoryreader.ir.recipes;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.github.moulberry.repo.NEURepository;
import io.github.moulberry.repo.NEURepositoryException;
import io.github.moulberry.repo.data.NEUCraftingRecipe;
import io.github.moulberry.repo.data.NEUForgeRecipe;
import io.github.moulberry.repo.data.NEUNpcShopRecipe;
import io.github.moulberry.repo.data.NEUIngredient;
import io.github.moulberry.repo.data.NEUKatUpgradeRecipe;
import io.github.moulberry.repo.data.NEUItem;
import io.github.moulberry.repo.data.NEURecipe;
import inventoryreader.ir.FilePathManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RemoteRecipeFetcher {
    private static final Logger LOGGER = LoggerFactory.getLogger("IR-RemoteRecipeFetcher");
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    public static final String DEFAULT_NEU_REPO_URL = "https://codeload.github.com/NotEnoughUpdates/NotEnoughUpdates-REPO/zip/refs/heads/master";
    /**
     * Bump when the way recipes are extracted from the repo changes, so cached snapshots are rebuilt
     * even if the remote reports "not modified".
     */
    private static final String PARSER_VERSION = "6";
    /** NEU's pseudo item for coin costs in shop recipes. */
    private static final String COIN_ID = "SKYBLOCK_COIN";
    public static final String COINS_NAME = "Coins";
    private static final String[] PET_RARITIES = {"Common", "Uncommon", "Rare", "Epic", "Legendary", "Mythic"};
    private static final String PARSER_VERSION_KEY = "parser-version";
    private static final long MAX_ZIP_ENTRIES = 200_000;
    private static final long MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "IR-RecipeFetch");
        t.setDaemon(true);
        return t;
    });
    private RemoteRecipeFetcher() {}

    /** Starts a background fetch unless one is already running. */
    public static void fetchAsync() {
        if (!RUNNING.compareAndSet(false, true)) return;
        EXECUTOR.execute(() -> {
            try {
                runFetch();
            } catch (Throwable t) {
                LOGGER.warn("Remote fetch failed: {}", t.toString());
            } finally {
                // The unpacked repo is only needed while parsing; the results live in the recipe JSON files.
                deleteDirectoryRecursively(FilePathManager.NEU_REPO_EXTRACTED.toPath());
                RUNNING.set(false);
            }
        });
    }

    private static void runFetch() throws Exception {
        File cfgFile = FilePathManager.REMOTE_SOURCES_JSON;
        if (!cfgFile.exists()) return;
        List<Map<String, String>> sources = readSources(cfgFile);
        if (sources.isEmpty()) return;

        for (Map<String, String> s : sources) {
            String type = String.valueOf(s.getOrDefault("type", "")).toLowerCase();
            String url = s.get("url");
            if (url == null || url.isBlank()) continue;
            boolean done = false;
            switch (type) {
                case "recipes":
                case "json":
                    done = fetchDirectJson(url);
                    break;
                case "neu-zip":
                case "neu_zip":
                case "neuzip":
                    done = fetchNeuZip(url);
                    break;
                default:
                    break;
            }
            if (done) return;
        }
    }

    private static boolean fetchDirectJson(String url) {
        try {
            if (!isHttps(url)) { LOGGER.warn("Ignoring non-https recipe source {}", url); return false; }
            Map<String, String> meta = readMeta(FilePathManager.REMOTE_META_JSON);
            String etagKey = "etag::" + url;
            String etag = cacheIsCurrent(meta) ? meta.getOrDefault(etagKey, "") : "";

            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET();
            if (!etag.isEmpty()) b.header("If-None-Match", etag);
            HttpResponse<String> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 304) { LOGGER.info("Remote recipes not modified (ETag)"); return true; }
            if (resp.statusCode() / 100 != 2) { LOGGER.warn("Remote fetch HTTP {}", resp.statusCode()); return false; }

            String body = resp.body();
            if (body == null || body.isBlank()) return false;
            java.lang.reflect.Type t = new TypeToken<Map<String, Object>>(){}.getType();
            Map<String, Object> parsed = GSON.fromJson(body, t);
            if (parsed == null || parsed.isEmpty()) { LOGGER.warn("Remote recipes JSON empty"); return false; }

            writeRemoteSnapshot(parsed);

            String newEtag = resp.headers().firstValue("etag").orElse("");
            if (!newEtag.isEmpty()) meta.put(etagKey, newEtag);
            meta.put(PARSER_VERSION_KEY, PARSER_VERSION);
            writeMeta(FilePathManager.REMOTE_META_JSON, meta);

            inventoryreader.ir.RecipeManager.getInstance().reload();
            return true;
        } catch (Exception e) {
            LOGGER.warn("fetchDirectJson failed: {}", e.toString());
            return false;
        }
    }

    private static boolean fetchNeuZip(String url) {
        try {
            Map<String, String> meta = readMeta(FilePathManager.REMOTE_META_JSON);
            boolean cacheCurrent = cacheIsCurrent(meta);

            InputStream inputStream;
            String metaKey;
            String metaValToWrite = null;

            try {
                URI u = URI.create(url);
                String scheme = u.getScheme();
                if (scheme != null && scheme.equalsIgnoreCase("file")) {
                    java.io.File f = new java.io.File(u);
                    if (!f.exists()) { LOGGER.warn("NEU ZIP file does not exist: {}", f.getAbsolutePath()); return false; }
                    metaKey = "mtime::" + f.getAbsolutePath();
                    String prev = meta.getOrDefault(metaKey, "");
                    String cur = Long.toString(f.lastModified());
                    if (cacheCurrent && !prev.isEmpty() && prev.equals(cur)) { LOGGER.info("NEU ZIP file unchanged (mtime cache)"); return true; }
                    inputStream = new java.io.FileInputStream(f);
                    metaValToWrite = cur;
                } else {
                    if (!"https".equalsIgnoreCase(scheme)) { LOGGER.warn("Ignoring non-https recipe source {}", url); return false; }
                    String etagKey = "etag::" + url;
                    String etag = cacheCurrent ? meta.getOrDefault(etagKey, "") : "";
                    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(30))
                            .GET();
                    if (!etag.isEmpty()) b.header("If-None-Match", etag);
                    HttpResponse<java.io.InputStream> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
                    if (resp.statusCode() / 100 != 2) {
                        resp.body().close();
                        if (resp.statusCode() == 304) { LOGGER.info("NEU ZIP not modified (ETag)"); return true; }
                        LOGGER.warn("NEU ZIP fetch HTTP {}", resp.statusCode());
                        return false;
                    }
                    inputStream = resp.body();
                    metaKey = etagKey;
                    metaValToWrite = resp.headers().firstValue("etag").orElse("");
                }
            } catch (IllegalArgumentException badUri) {
                java.io.File f = new java.io.File(url);
                if (!f.exists()) { LOGGER.warn("NEU ZIP path not found: {}", url); return false; }
                metaKey = "mtime::" + f.getAbsolutePath();
                String prev = meta.getOrDefault(metaKey, "");
                String cur = Long.toString(f.lastModified());
                if (cacheCurrent && !prev.isEmpty() && prev.equals(cur)) { LOGGER.info("NEU ZIP file unchanged (mtime cache)"); return true; }
                inputStream = new java.io.FileInputStream(f);
                metaValToWrite = cur;
            }

            Path repoExtracted = FilePathManager.NEU_REPO_EXTRACTED.toPath();
            deleteDirectoryRecursively(repoExtracted);
            try (InputStream in = inputStream) {
                extractZipStrippingRoot(in, repoExtracted);
            }
            LOGGER.info("NEU ZIP extracted to {}", repoExtracted);

            NEURepository neuRepo = NEURepository.of(repoExtracted);
            try {
                neuRepo.reload();
            } catch (NEURepositoryException e) {
                LOGGER.warn("NEU repo load had issues: {}", e.toString());
                if (neuRepo.isIncomplete()) { LOGGER.warn("Repo incomplete after reload, aborting"); return false; }
            }

            Map<String, String> internalToDisplay = new LinkedHashMap<>();
            for (NEUItem item : neuRepo.getItems().getItems().values()) {
                String id = item.getSkyblockItemId();
                String display = stripMC(item.getDisplayName());
                if (id != null && !id.isBlank() && display != null && !display.isBlank()) {
                    internalToDisplay.putIfAbsent(id, petName(id, display));
                }
            }
            internalToDisplay.put(COIN_ID, COINS_NAME);

            Map<String, Map<String, Integer>> craftingByInternal = new LinkedHashMap<>();
            Map<String, Map<String, Integer>> forgeByInternal    = new LinkedHashMap<>();
            Map<String, Map<String, Integer>> shopByInternal     = new LinkedHashMap<>();
            Map<String, Map<String, Integer>> katByInternal      = new LinkedHashMap<>();
            for (NEUItem item : neuRepo.getItems().getItems().values()) {
                for (NEURecipe recipe : item.getRecipes()) {
                    if (recipe instanceof NEUCraftingRecipe cr) {
                        collectRecipeIngredients(craftingByInternal, cr.getAllOutputs(), cr.getAllInputs());
                    } else if (recipe instanceof NEUForgeRecipe fr) {
                        collectRecipeIngredients(forgeByInternal, fr.getAllOutputs(), fr.getAllInputs());
                    } else if (recipe instanceof NEUNpcShopRecipe shop) {
                        collectRecipeIngredients(shopByInternal, shop.getAllOutputs(), shop.getAllInputs());
                    } else if (recipe instanceof NEUKatUpgradeRecipe kat) {
                        collectRecipeIngredients(katByInternal, kat.getAllOutputs(), kat.getAllInputs());
                    }
                }
            }

            // Forge recipes win over crafting recipes for the same item; keeping both would double-count.
            craftingByInternal.keySet().removeAll(forgeByInternal.keySet());
            // Shop purchases only fill gaps (items with no crafting/forge recipe, e.g. the Golden Dragon), and only
            // when they cost items: a coins-only price would turn ordinary materials into "buy it for coins".
            shopByInternal.keySet().removeAll(craftingByInternal.keySet());
            shopByInternal.keySet().removeAll(forgeByInternal.keySet());
            shopByInternal.values().removeIf(cost -> cost.keySet().stream().allMatch(COIN_ID::equals));
            // Kat upgrades (the pet one rarity lower + items + coins) cover most pets above Common. They fill
            // the remaining gaps and travel with the crafting recipes.
            katByInternal.keySet().removeAll(forgeByInternal.keySet());
            katByInternal.keySet().removeAll(shopByInternal.keySet());
            int katCount = 0;
            for (Map.Entry<String, Map<String, Integer>> e : katByInternal.entrySet()) {
                if (craftingByInternal.putIfAbsent(e.getKey(), e.getValue()) == null) katCount++;
            }
            Map<String, String> recipeNameById = new LinkedHashMap<>(internalToDisplay);
            Map<String, Map<String, Integer>> craftingWire = resolveToDisplayNames(craftingByInternal, internalToDisplay, recipeNameById);
            Map<String, Map<String, Integer>> forgeWire    = resolveToDisplayNames(forgeByInternal,    internalToDisplay, recipeNameById);
            Map<String, Map<String, Integer>> shopWire     = resolveToDisplayNames(shopByInternal,     internalToDisplay, recipeNameById);

            if (!craftingWire.isEmpty()) writeRemoteSnapshot(craftingWire);
            if (!forgeWire.isEmpty())    writeForgeSnapshot(forgeWire);
            writeSnapshot(shopWire, FilePathManager.REMOTE_SHOP_JSON, "recipes_remote_shop.json.tmp");
            writeSnapshot(recipeNameById, FilePathManager.ITEM_NAMES_JSON, "item_names.json.tmp");
            inventoryreader.ir.ItemIds.reload();

            LOGGER.info("NEU repo parsed (library): {} crafting (incl. {} pet upgrades), {} forge, {} shop recipes", craftingWire.size(), katCount, forgeWire.size(), shopWire.size());
            inventoryreader.ir.RecipeManager.getInstance().reload();

            if (metaValToWrite != null && !metaValToWrite.isEmpty()) {
                meta.put(metaKey, metaValToWrite);
            }
            meta.put(PARSER_VERSION_KEY, PARSER_VERSION);
            writeMeta(FilePathManager.REMOTE_META_JSON, meta);
            return true;
        } catch (Exception e) {
            LOGGER.warn("fetchNeuZip failed: {}", e.toString());
            return false;
        }
    }

    /**
     * Adds an {@code output → {ingredient: count per output item}} mapping into {@code target}, keyed by
     * internal SkyBlock ID. Only the first recipe per output is kept: items with alternative recipes
     * would otherwise have the ingredients of every alternative summed together.
     */
    private static void collectRecipeIngredients(
            Map<String, Map<String, Integer>> target,
            Collection<NEUIngredient> outputs,
            Collection<NEUIngredient> inputs) {
        if (outputs == null || outputs.isEmpty() || inputs == null) return;
        NEUIngredient output = outputs.iterator().next();
        if (output == null || NEUIngredient.NEU_SENTINEL_EMPTY.equals(output.getItemId())) return;
        if (target.containsKey(output.getItemId())) return;
        double outputCount = Math.max(1, output.getAmount());
        Map<String, Double> totals = new LinkedHashMap<>();
        for (NEUIngredient in : inputs) {
            // Skips free parts too, e.g. the zero-coin cost of some pet upgrades.
            if (in == null || NEUIngredient.NEU_SENTINEL_EMPTY.equals(in.getItemId()) || in.getAmount() <= 0) continue;
            totals.merge(in.getItemId(), in.getAmount(), Double::sum);
        }
        if (totals.isEmpty()) return;
        Map<String, Integer> ing = new LinkedHashMap<>();
        totals.forEach((id, amount) -> ing.put(id, (int) Math.max(1, Math.ceil(amount / outputCount))));
        target.put(output.getItemId(), ing);
    }

    /**
     * Returns a new map with all internal SkyBlock IDs replaced by their display names. Outputs that had
     * to be renamed to stay unique are recorded in {@code recipeNameById}.
     */
    private static Map<String, Map<String, Integer>> resolveToDisplayNames(
            Map<String, Map<String, Integer>> byInternal,
            Map<String, String> internalToDisplay,
            Map<String, String> recipeNameById) {
        Map<String, Map<String, Integer>> wire = new LinkedHashMap<>(byInternal.size());
        for (Map.Entry<String, Map<String, Integer>> e : byInternal.entrySet()) {
            String outDisplay = internalToDisplay.getOrDefault(e.getKey(), e.getKey());
            // Different items can share a display name (e.g. upgrade stones); keep them apart.
            if (wire.containsKey(outDisplay)) {
                outDisplay = outDisplay + " [" + e.getKey() + "]";
                recipeNameById.put(e.getKey(), outDisplay);
            }
            Map<String, Integer> ingDisplay = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> in : e.getValue().entrySet()) {
                ingDisplay.put(internalToDisplay.getOrDefault(in.getKey(), in.getKey()), in.getValue());
            }
            wire.put(outDisplay, ingDisplay);
        }
        return wire;
    }

    /**
     * Extracts a ZIP input stream into {@code targetDir}, stripping the single top-level directory
     * that GitHub archive ZIPs always include (e.g. {@code NotEnoughUpdates-REPO-{sha}/}).
     */
    private static void extractZipStrippingRoot(InputStream inputStream, Path targetDir) throws Exception {
        Files.createDirectories(targetDir);
        long entries = 0;
        long bytes = 0;
        try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(inputStream)) {
            java.util.zip.ZipEntry ze;
            while ((ze = zin.getNextEntry()) != null) {
                if (ze.isDirectory()) continue;
                if (++entries > MAX_ZIP_ENTRIES) throw new java.io.IOException("ZIP has too many entries");
                String name = ze.getName();
                int slash = name.indexOf('/');
                if (slash < 0) continue;                        // no subdirectory — skip
                String stripped = name.substring(slash + 1);
                if (stripped.isBlank()) continue;
                Path dest = targetDir.resolve(stripped).normalize();
                if (!dest.startsWith(targetDir)) {              // guard against path traversal
                    LOGGER.error("ZIP path traversal blocked: {}", name);
                    continue;
                }
                Files.createDirectories(dest.getParent());
                bytes += Files.copy(zin, dest, StandardCopyOption.REPLACE_EXISTING);
                if (bytes > MAX_EXTRACTED_BYTES) throw new java.io.IOException("ZIP extracts to more than " + MAX_EXTRACTED_BYTES + " bytes");
            }
        }
    }

    /** Recursively deletes {@code dir} and all its contents, silently ignoring errors. */
    private static void deleteDirectoryRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (Exception ignore) {} });
        } catch (Exception ignore) {}
    }

    private static void writeRemoteSnapshot(Map<String, ?> data) throws Exception {
        writeSnapshot(data, FilePathManager.REMOTE_RECIPES_JSON, "recipes_remote.json.tmp");
    }

    private static void writeForgeSnapshot(Map<String, ?> data) throws Exception {
        writeSnapshot(data, FilePathManager.REMOTE_FORGE_JSON, "recipes_remote_forge.json.tmp");
    }

    private static void writeSnapshot(Map<String, ?> data, File dest, String tmpName) throws Exception {
        File tmp = new File(FilePathManager.DATA_DIR, tmpName);
        try (FileWriter fw = new FileWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(data, fw);
        }
        try {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<Map<String, String>> readSources(File f) {
        try (FileReader fr = new FileReader(f, StandardCharsets.UTF_8)) {
            java.lang.reflect.Type t = new TypeToken<Map<String, Object>>(){}.getType();
            Map<String, Object> root = GSON.fromJson(fr, t);
            Object arr = root == null ? null : root.get("sources");
            List<Map<String, String>> out = new ArrayList<>();
            if (arr instanceof List<?>) {
                for (Object o : (List<?>) arr) {
                    if (o instanceof Map<?, ?> m) {
                        Map<String, String> entry = new LinkedHashMap<>();
                        Object type = m.get("type");
                        Object url = m.get("url");
                        if (type != null && url != null) {
                            entry.put("type", String.valueOf(type));
                            entry.put("url", String.valueOf(url));
                            out.add(entry);
                        }
                    }
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Map<String, String> readMeta(File f) {
        if (!f.exists()) return new LinkedHashMap<>();
        try (FileReader fr = new FileReader(f, StandardCharsets.UTF_8)) {
            java.lang.reflect.Type t = new TypeToken<Map<String, String>>(){}.getType();
            Map<String, String> m = GSON.fromJson(fr, t);
            return m == null ? new LinkedHashMap<>() : m;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static void writeMeta(File f, Map<String, String> meta) {
        try (FileWriter fw = new FileWriter(f, StandardCharsets.UTF_8)) {
            GSON.toJson(meta, fw);
        } catch (Exception ignored) {}
    }

    /**
     * Pets are listed per rarity ("BEE;4") with a level placeholder in the name ("[Lvl {LVL}] Bee"). Use
     * "Bee (Legendary)" so the rarities stay apart and the name reads well.
     */
    private static String petName(String id, String display) {
        int semi = id.lastIndexOf(';');
        if (semi < 0 || !display.startsWith("[Lvl")) return display;
        String name = display.replaceFirst("^\\[Lvl [^\\]]*\\]\\s*", "");
        try {
            int rarity = Integer.parseInt(id.substring(semi + 1));
            if (rarity >= 0 && rarity < PET_RARITIES.length) return name + " (" + PET_RARITIES[rarity] + ")";
        } catch (NumberFormatException ignored) {
            // Not a pet rarity suffix; keep the plain name.
        }
        return name;
    }

    private static boolean cacheIsCurrent(Map<String, String> meta) {
        return PARSER_VERSION.equals(meta.get(PARSER_VERSION_KEY));
    }

    private static boolean isHttps(String url) {
        try {
            return "https".equalsIgnoreCase(URI.create(url).getScheme());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String stripMC(String s) {
        return inventoryreader.ir.ItemNames.clean(s);
    }
}
