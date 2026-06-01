package net.arm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextDumper {
    private static final Logger LOGGER = LoggerFactory.getLogger("Transletar");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Path CACHE_PATH = FabricLoader.getInstance().getConfigDir().resolve("transletar_cache.json");
    private static final Path PROGRESS_PATH = FabricLoader.getInstance().getConfigDir().resolve("transletar_progress.json");

    private static final Map<String, String> cachedTranslations = new HashMap<>();
    private static String currentTargetLang = "ru";

    private static final Pattern ENGLISH_ALPHABET = Pattern.compile(".*[a-zA-Z].*");
    private static final Pattern FORMAT_PATTERN = Pattern.compile("%(?:\\d+\\$)?[-#+ 0,]*\\d*(?:\\.\\d+)?[a-zA-Z%]");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\[\\s*#\\s*(\\d+)\\s*\\]");

    private static final ConcurrentLinkedQueue<String[]> PENDING_TRANSLATIONS = new ConcurrentLinkedQueue<>();
    private static final Set<String> REQUESTED_KEYS = ConcurrentHashMap.newKeySet();
    private static boolean daemonStarted = false;

    public static void setTargetLang(String lang) {
        currentTargetLang = lang;
    }

    public static String getTargetLang() {
        return currentTargetLang;
    }

    static {
        loadCacheFromFile();
    }
    private static boolean isNativeTranslationInvalid(String targetLang, String translatedText) {
        if (targetLang.equalsIgnoreCase("en")) {
            return false;
        }
        return translatedText != null && ENGLISH_ALPHABET.matcher(translatedText).matches();
    }
    public static void loadCacheFromFile() {
        if (Files.exists(CACHE_PATH)) {
            try (BufferedReader reader = Files.newBufferedReader(CACHE_PATH, StandardCharsets.UTF_8)) {
                JsonObject json = GSON.fromJson(reader, JsonObject.class);
                if (json != null) {
                    synchronized (cachedTranslations) {
                        cachedTranslations.clear();
                        for (Map.Entry<String, com.google.gson.JsonElement> entry : json.entrySet()) {
                            if (entry.getValue().isJsonPrimitive()) {
                                cachedTranslations.put(entry.getKey(), entry.getValue().getAsString());
                            }
                        }
                    }
                    LOGGER.info("Loaded " + cachedTranslations.size() + " translations from local cache.");
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load translation cache", e);
            }
        }
    }

    private static void saveCacheToFile() {
        try {
            Files.createDirectories(CACHE_PATH.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(CACHE_PATH, StandardCharsets.UTF_8)) {
                JsonObject json = new JsonObject();
                synchronized (cachedTranslations) {
                    new TreeMap<>(cachedTranslations).forEach(json::addProperty);
                }
                GSON.toJson(json, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save translation cache", e);
        }
    }

    private static void writeProgressConfig(int processed, int total, String status, boolean finished) {
        try {
            Files.createDirectories(PROGRESS_PATH.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(PROGRESS_PATH, StandardCharsets.UTF_8)) {
                JsonObject progressJson = new JsonObject();
                progressJson.addProperty("processed", processed);
                progressJson.addProperty("total", total);
                progressJson.addProperty("status", status);
                progressJson.addProperty("is_finished", finished);
                progressJson.addProperty("percentage", total > 0 ? (int) (((float) processed / total) * 100) : 0);
                GSON.toJson(progressJson, writer);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to write progress config", e);
        }
    }

    public static String getOrRequestTranslationOnTheFly(String key, String originalText) {
        if (originalText == null || originalText.trim().isEmpty()) {
            return originalText;
        }

        if (cachedTranslations.containsKey(key)) {
            return cachedTranslations.get(key);
        }

        if (originalText.matches(".*[a-zA-Z\\p{L}].*") && !originalText.contains("%s")) {
            if (REQUESTED_KEYS.add(key)) {
                PENDING_TRANSLATIONS.add(new String[]{key, originalText});
                startDaemonIfNeeded();
            }
        }

        return originalText;
    }

    private static synchronized void startDaemonIfNeeded() {
        if (daemonStarted) return;
        daemonStarted = true;

        Thread daemon = new Thread(() -> {
            while (true) {
                try {
                    if (!PENDING_TRANSLATIONS.isEmpty()) {
                        List<String> keysBatch = new ArrayList<>();
                        List<String> valuesBatch = new ArrayList<>();
                        int count = 0;

                        while (!PENDING_TRANSLATIONS.isEmpty() && count < 20) {
                            String[] task = PENDING_TRANSLATIONS.poll();
                            if (task != null) {
                                keysBatch.add(task[0]);
                                valuesBatch.add(task[1]);
                                count++;
                            }
                        }

                        if (!keysBatch.isEmpty()) {
                            Map<String, String> resultMap = new TreeMap<>();
                            translateBatch(keysBatch, valuesBatch, resultMap, null, currentTargetLang);

                            boolean cacheChanged = false;
                            synchronized (cachedTranslations) {
                                for (Map.Entry<String, String> entry : resultMap.entrySet()) {
                                    if (!entry.getValue().contains("[Error]") && !entry.getValue().contains("[API Error]")) {
                                        cachedTranslations.put(entry.getKey(), entry.getValue());
                                        cacheChanged = true;
                                    } else {
                                        REQUESTED_KEYS.remove(entry.getKey());
                                    }
                                }
                            }
                            if (cacheChanged) saveCacheToFile();
                        }
                    }
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    LOGGER.error("Translation daemon error", e);
                }
            }
        });
        daemon.setDaemon(true);
        daemon.setName("Transletar-Daemon");
        daemon.start();
    }


    public static void runDownloadAndTranslationAsync(TranslationProgressScreen screen, String lang) {
        Thread thread = new Thread(() -> {
            try {
                setTargetLang(lang);

                screen.updateProgress(0.05f, "Scanning mods...");
                Map<String, String> allTranslations = scanAllModStrings();

                screen.updateProgress(0.1f, "Checking built-in translations...");
                Map<String, String> nativeTranslations = loadNativeTranslationsForLang(lang);
                screen.addLog("Found " + nativeTranslations.size() + " built-in translations for " + lang);

                Map<String, String> resultMap = new TreeMap<>();
                List<String> keysBatch = new ArrayList<>();
                List<String> valuesBatch = new ArrayList<>();

                int nativeCount = 0;
                int skippedNativeCount = 0;

                for (Map.Entry<String, String> entry : allTranslations.entrySet()) {
                    String key = entry.getKey();
                    if (nativeTranslations.containsKey(key)) {
                        String nativeTrans = nativeTranslations.get(key);

                        if (isNativeTranslationInvalid(lang, nativeTrans)) {
                            skippedNativeCount++;
                        } else {
                            resultMap.put(key, nativeTrans);
                            nativeCount++;
                        }
                    }
                }

                if (nativeCount > 0) {
                    screen.addLog("Using high-quality built-in translations for " + nativeCount + " strings.");
                }
                if (skippedNativeCount > 0) {
                    screen.addLog("Found " + skippedNativeCount + " untranslated (English) stubs in local lang file. Sending to Google...");
                }
                if (nativeCount > 0) {
                    screen.addLog("Using built-in translations for " + nativeCount + " strings.");
                }

                int totalProcessed = 0;
                int totalSize = allTranslations.size();

                for (Map.Entry<String, String> entry : allTranslations.entrySet()) {
                    if (cachedTranslations.containsKey(entry.getKey()) || resultMap.containsKey(entry.getKey())) continue;

                    keysBatch.add(entry.getKey());
                    valuesBatch.add(entry.getValue());

                    if (keysBatch.size() >= 25) {
                        totalProcessed += keysBatch.size();
                        screen.updateProgress(0.1f + ((float) totalProcessed / totalSize) * 0.85f, "Translating... (" + totalProcessed + "/" + totalSize + ")");
                        translateBatch(keysBatch, valuesBatch, resultMap, screen, lang);
                        keysBatch.clear();
                        valuesBatch.clear();
                    }
                }
                if (!keysBatch.isEmpty()) translateBatch(keysBatch, valuesBatch, resultMap, screen, lang);

                synchronized (cachedTranslations) {
                    cachedTranslations.putAll(resultMap);
                }
                saveCacheToFile();

                injectTranslations();

                screen.addLog("Download and Apply complete!");
                screen.markAsFinished();
            } catch (Exception e) {
                screen.addLog("Error: " + e.getMessage());
                screen.markAsFinished();
            }
        });
        thread.start();
    }

    private static void translateBatch(List<String> keys, List<String> values, Map<String, String> resultMap, TranslationProgressScreen screen, String targetLang) {
        try {
            List<List<String>> batchFormatTags = new ArrayList<>();
            StringBuilder textToTranslateBuilder = new StringBuilder();

            for (String originalText : values) {
                List<String> tags = new ArrayList<>();
                Matcher matcher = FORMAT_PATTERN.matcher(originalText);
                StringBuffer sb = new StringBuffer();
                int index = 0;

                while (matcher.find()) {
                    tags.add(matcher.group());
                    matcher.appendReplacement(sb, "[#" + index + "]");
                    index++;
                }
                matcher.appendTail(sb);

                batchFormatTags.add(tags);
                textToTranslateBuilder.append(sb.toString()).append("\n");
            }

            String textToTranslate = textToTranslateBuilder.toString();

            String urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl="
                    + targetLang + "&dt=t&q=" + URLEncoder.encode(textToTranslate, "UTF-8");
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    JsonArray jsonArray = GSON.fromJson(reader, JsonArray.class);
                    JsonArray textChunks = jsonArray.get(0).getAsJsonArray();
                    StringBuilder fullTranslation = new StringBuilder();
                    for (int i = 0; i < textChunks.size(); i++) {
                        fullTranslation.append(textChunks.get(i).getAsJsonArray().get(0).getAsString());
                    }

                    String[] translatedLines = fullTranslation.toString().split("\n", -1);
                    for (int i = 0; i < keys.size(); i++) {
                        String key = keys.get(i);
                        String translatedText = (i < translatedLines.length) ? translatedLines[i].trim() : "[Error]";

                        if (i < translatedLines.length && i < batchFormatTags.size()) {
                            List<String> tags = batchFormatTags.get(i);
                            if (!tags.isEmpty()) {
                                Matcher restoreMatcher = PLACEHOLDER_PATTERN.matcher(translatedText);
                                StringBuffer restoreSb = new StringBuffer();
                                while (restoreMatcher.find()) {
                                    int idx = java.lang.Integer.parseInt(restoreMatcher.group(1));
                                    if (idx >= 0 && idx < tags.size()) {
                                        restoreMatcher.appendReplacement(restoreSb, Matcher.quoteReplacement(tags.get(idx)));
                                    } else {
                                        restoreMatcher.appendReplacement(restoreSb, Matcher.quoteReplacement(restoreMatcher.group()));
                                    }
                                }
                                restoreMatcher.appendTail(restoreSb);
                                translatedText = restoreSb.toString();
                            }
                        }

                        resultMap.put(key, translatedText);

                        if (screen != null) {
                            screen.addLog(keys.get(i) + " -> " + translatedText);
                        }
                    }
                    return;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Translation failed", e);
        }
        for (String key : keys) resultMap.put(key, "[API Error]");
    }

    private static Map<String, String> scanAllModStrings() {
        Map<String, String> allTranslations = new TreeMap<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String modId = mod.getMetadata().getId();
            Path langPath = mod.findPath("assets/" + modId + "/lang/en_us.json").orElse(null);
            if (langPath != null) {
                try (BufferedReader reader = Files.newBufferedReader(langPath)) {
                    JsonObject jsonObject = GSON.fromJson(reader, JsonObject.class);
                    if (jsonObject != null) {
                        for (Map.Entry<String, com.google.gson.JsonElement> entry : jsonObject.entrySet()) {
                            if (entry.getValue().isJsonPrimitive())
                                allTranslations.put(entry.getKey(), entry.getValue().getAsString());
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return allTranslations;
    }

    private static Map<String, String> loadNativeTranslationsForLang(String lang) {
        Map<String, String> nativeMap = new HashMap<>();
        String suffix = getLangSuffix(lang);

        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String modId = mod.getMetadata().getId();

            Path langPath = mod.findPath("assets/" + modId + "/lang/" + suffix + ".json").orElse(null);
            if (langPath == null) {
                langPath = mod.findPath("assets/" + modId + "/lang/" + lang.toLowerCase() + ".json").orElse(null);
            }

            if (langPath != null) {
                try (BufferedReader reader = Files.newBufferedReader(langPath, StandardCharsets.UTF_8)) {
                    JsonObject jsonObject = GSON.fromJson(reader, JsonObject.class);
                    if (jsonObject != null) {
                        for (Map.Entry<String, com.google.gson.JsonElement> entry : jsonObject.entrySet()) {
                            if (entry.getValue().isJsonPrimitive()) {
                                nativeMap.put(entry.getKey(), entry.getValue().getAsString());
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return nativeMap;
    }

    private static String getLangSuffix(String lang) {
        switch (lang.toLowerCase()) {
            case "ru": return "ru_ru";
            case "en": return "en_us";
            case "de": return "de_de";
            case "fr": return "fr_fr";
            case "es": return "es_es";
            case "it": return "it_it";
            case "pt": return "pt_br";
            case "pl": return "pl_pl";
            case "tr": return "tr_tr";
            case "ja": return "ja_jp";
            case "ko": return "ko_kr";
            case "zh": return "zh_cn";
            case "nl": return "nl_nl";
            case "sv": return "sv_se";
            case "cs": return "cs_cz";
            case "uk": return "uk_ua";
            default: return lang.toLowerCase() + "_" + lang.toLowerCase();
        }
    }



    public static boolean injectTranslations() {
        synchronized (cachedTranslations) {
            final Language vanillaLanguage = Language.getInstance();

            Language customLanguage = new Language() {
                @Override
                public String getOrDefault(String key, String def) {
                    if (vanillaLanguage.has(key)) {
                        String nativeTrans = vanillaLanguage.getOrDefault(key, def);
                        if (nativeTrans != null && !nativeTrans.trim().isEmpty() && !nativeTrans.equals(def)) {
                            if (!isNativeTranslationInvalid(currentTargetLang, nativeTrans)) {
                                return nativeTrans;
                            }
                        }
                    }

                    String original = vanillaLanguage.getOrDefault(key, def);

                    if (original == null || original.trim().isEmpty()) {
                        return original;
                    }

                    if (key != null && key.contains(":") && !key.startsWith("literal:")) {
                        return original;
                    }

                    if (original.equalsIgnoreCase("%s") || original.matches("^[^\\w\\p{L}]+$")) {
                        return original;
                    }

                    return getOrRequestTranslationOnTheFly(key, original);
                }

                @Override
                public boolean has(String key) {
                    return vanillaLanguage.has(key) || cachedTranslations.containsKey(key);
                }

                @Override
                public boolean isDefaultRightToLeft() {
                    return vanillaLanguage.isDefaultRightToLeft();
                }

                @Override
                public FormattedCharSequence getVisualOrder(FormattedText text) {
                    return vanillaLanguage.getVisualOrder(text);
                }
            };

            Language.inject(customLanguage);
            return true;
        }
    }
    public static void deleteOldCache() {
        synchronized (cachedTranslations) {
            cachedTranslations.clear();
            try {
                if (java.nio.file.Files.deleteIfExists(CACHE_PATH)) {
                    LOGGER.info("Old translation cache deleted successfully.");
                }
            } catch (java.io.IOException e) {
                LOGGER.error("Failed to delete old translation cache file", e);
            }
        }
    }
    public static Map<String, String> getCachedTranslations() {
        return cachedTranslations;
    }
}