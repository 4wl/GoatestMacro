package com.justingoat.goat.client.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BazaarPriceCache {
    private static final URI BAZAAR_URI = URI.create("https://api.hypixel.net/v2/skyblock/bazaar");
    private static final long REFRESH_INTERVAL_MS = Duration.ofMinutes(2).toMillis();
    private static final long FAILED_RETRY_MS = Duration.ofSeconds(20).toMillis();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .build();

    private static final AtomicBoolean FETCHING = new AtomicBoolean(false);
    private static volatile Map<String, BazaarItem> byNormalizedName = Collections.emptyMap();
    private static volatile Map<String, BazaarItem> byProductId = Collections.emptyMap();
    private static volatile long nextFetchAt = 0L;
    private static volatile long lastSuccessfulFetch = 0L;
    private static volatile String lastError = "";

    private BazaarPriceCache() {
    }

    public static void updateIfNeeded() {
        long now = System.currentTimeMillis();
        if (now < nextFetchAt || !FETCHING.compareAndSet(false, true)) return;
        nextFetchAt = now + REFRESH_INTERVAL_MS;

        HttpRequest request = HttpRequest.newBuilder(BAZAAR_URI)
            .timeout(Duration.ofSeconds(12))
            .header("User-Agent", "GoatClient/VisitorsMacro")
            .GET()
            .build();

        CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<java.io.InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }

                try (InputStreamReader reader = new InputStreamReader(response.body(), StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    if (!root.has("success") || !root.get("success").getAsBoolean()) {
                        throw new IllegalStateException("success=false");
                    }
                    JsonObject products = root.getAsJsonObject("products");
                    Map<String, BazaarItem> nameMap = new HashMap<>();
                    Map<String, BazaarItem> idMap = new HashMap<>();

                    for (Map.Entry<String, JsonElement> entry : products.entrySet()) {
                        if (!entry.getValue().isJsonObject()) continue;
                        String productId = entry.getKey();
                        JsonObject product = entry.getValue().getAsJsonObject();
                        BazaarItem item = parseProduct(productId, product);
                        if (item == null) continue;
                        idMap.put(productId, item);
                        nameMap.put(normalize(item.displayName()), item);
                        nameMap.put(normalize(productId), item);
                    }

                    byNormalizedName = Map.copyOf(nameMap);
                    byProductId = Map.copyOf(idMap);
                    lastSuccessfulFetch = System.currentTimeMillis();
                    lastError = "";
                }
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                nextFetchAt = System.currentTimeMillis() + FAILED_RETRY_MS;
            } finally {
                FETCHING.set(false);
            }
        });
    }

    public static Optional<BazaarItem> findByName(String displayName) {
        updateIfNeeded();
        String normalized = normalize(displayName);
        BazaarItem exact = byNormalizedName.get(normalized);
        if (exact != null) return Optional.of(exact);

        String fallback = normalize(displayName.replace("Enchanted ", "ENCHANTED_"));
        exact = byNormalizedName.get(fallback);
        if (exact != null) return Optional.of(exact);

        return byNormalizedName.entrySet().stream()
            .filter(entry -> entry.getKey().equals(normalized) || entry.getKey().endsWith(normalized))
            .map(Map.Entry::getValue)
            .findFirst();
    }

    public static Optional<BazaarItem> findByProductId(String productId) {
        updateIfNeeded();
        return Optional.ofNullable(byProductId.get(productId));
    }

    public static double getCopperValue() {
        return findByProductId("ENCHANTMENT_GREEN_THUMB_1")
            .map(item -> item.instantBuyPrice() / 1500.0)
            .orElse(0.0);
    }

    public static boolean hasPrices() {
        return !byProductId.isEmpty();
    }

    public static long getLastSuccessfulFetch() {
        return lastSuccessfulFetch;
    }

    public static String getLastError() {
        return lastError;
    }

    public static String normalize(String text) {
        if (text == null) return "";
        String stripped = StringUtils.stripColor(text);
        stripped = stripped == null ? "" : stripped;
        return stripped.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static BazaarItem parseProduct(String productId, JsonObject product) {
        JsonObject quickStatus = product.has("quick_status") && product.get("quick_status").isJsonObject()
            ? product.getAsJsonObject("quick_status")
            : null;
        if (quickStatus == null) return null;

        double instantBuy = getDouble(quickStatus, "sellPrice");
        double instantSell = getDouble(quickStatus, "buyPrice");
        if (instantBuy <= 0.0 && instantSell <= 0.0) return null;

        return new BazaarItem(productId, displayName(productId), instantBuy, instantSell);
    }

    private static double getDouble(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) return 0.0;
        try {
            return object.get(key).getAsDouble();
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private static String displayName(String productId) {
        if (productId.startsWith("ENCHANTMENT_")) {
            String enchant = productId.substring("ENCHANTMENT_".length());
            int lastUnderscore = enchant.lastIndexOf('_');
            if (lastUnderscore > 0 && lastUnderscore < enchant.length() - 1) {
                String level = enchant.substring(lastUnderscore + 1);
                String name = prettify(enchant.substring(0, lastUnderscore));
                return name + " " + romanLevel(level);
            }
        }
        return switch (productId) {
            case "INK_SACK:3" -> "Cocoa Beans";
            case "ENCHANTED_INK_SACK:3" -> "Enchanted Cocoa Beans";
            case "CACTUS_GREEN" -> "Cactus Green";
            case "ENCHANTED_CACTUS_GREEN" -> "Enchanted Cactus Green";
            default -> prettify(productId);
        };
    }

    private static String prettify(String productId) {
        String[] parts = productId.toLowerCase(Locale.ROOT).split("[_:]");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank() || part.matches("\\d+")) continue;
            if (!builder.isEmpty()) builder.append(' ');
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }

    private static String romanLevel(String level) {
        return switch (level) {
            case "1" -> "I";
            case "2" -> "II";
            case "3" -> "III";
            case "4" -> "IV";
            case "5" -> "V";
            case "6" -> "VI";
            case "7" -> "VII";
            case "8" -> "VIII";
            case "9" -> "IX";
            case "10" -> "X";
            default -> level;
        };
    }

    public record BazaarItem(String productId, String displayName, double instantBuyPrice, double instantSellPrice) {
    }
}
