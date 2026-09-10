package com.offlineskin;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class SkinService {

    public static final String DEFAULT_NAME_API = "https://api.minecraftservices.com/minecraft/profile/lookup/name/";
    public static final String DEFAULT_FALLBACK_NAME_API = "https://api.mojang.com/users/profiles/minecraft/";
    public static final String DEFAULT_PROFILE_API = "https://sessionserver.mojang.com/session/minecraft/profile/";

    private static final String TEXTURES = "textures";
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{32}$");
    private static final int MAX_CACHE = 10_000;
    private static final int MAX_PENDING = 1000;
    private static final long RETRY_CAP_MS = 30_000L;
    private static final long RETRY_DEFAULT_MS = 5_000L;

    private final OfflineSkinPlugin plugin;
    private final HttpClient http;
    private final ThreadPoolExecutor executor;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Object rateLock = new Object();
    private long lastRequestAt;

    private record CacheEntry(ProfileProperty prop, long fetchedAt, long ttlMs) {
        boolean expired(long now) {
            return now - fetchedAt > ttlMs;
        }
    }

    public SkinService(OfflineSkinPlugin plugin) {
        this.plugin = plugin;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_PENDING), r -> {
                    Thread t = new Thread(r, "offlineskin-mojang");
                    t.setDaemon(true);
                    return t;
                });
    }

    public CompletableFuture<ProfileProperty> lookupAsync(String name) {
        if (executor.getQueue().size() >= MAX_PENDING) {
            return CompletableFuture.failedFuture(new IllegalStateException("查询队列已满，请稍后再试"));
        }
        try {
            return CompletableFuture.supplyAsync(() -> resolve(name), executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private ProfileProperty resolve(String name) {
        String key = name.toLowerCase();
        long now = System.currentTimeMillis();

        CacheEntry hit = cache.get(key);
        if (hit != null) {
            if (!hit.expired(now)) {
                return hit.prop();
            }
            cache.remove(key);
        }

        try {
            String uuid = nameToUuid(name);
            if (uuid == null) {
                remember(key, null, plugin.getNegativeTtlMs());
                return null;
            }
            return extract(key, sendGet(plugin.getProfileApiUrl() + uuid + "?unsigned=false"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private String nameToUuid(String name) throws IOException, InterruptedException {
        String[] endpoints = { plugin.getNameApiUrl(), plugin.getFallbackNameApiUrl() };
        List<String> failures = new ArrayList<>(2);
        for (String endpoint : endpoints) {
            try {
                HttpResponse<String> resp = sendGet(endpoint + URLEncoder.encode(name, StandardCharsets.UTF_8));
                if (resp.statusCode() == 200) {
                    JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
                    JsonElement idEl = obj.get("id");
                    String id = idEl != null && idEl.isJsonPrimitive() ? idEl.getAsString() : null;
                    if (id != null && UUID_PATTERN.matcher(id).matches()) {
                        return id;
                    }
                    failures.add("无效的 UUID 格式");
                } else if (resp.statusCode() == 404) {
                    return null;
                } else {
                    failures.add("HTTP " + resp.statusCode());
                }
            } catch (InterruptedException e) {
                throw e;
            } catch (IOException e) {
                failures.add(e.getMessage());
            } catch (RuntimeException e) {
                failures.add(e.getMessage());
            }
        }
        throw new IOException("所有名字查询端点都失败：" + String.join("；", failures));
    }

    private ProfileProperty extract(String key, HttpResponse<String> resp) throws IOException {
        int status = resp.statusCode();
        if (status == 404 || status == 204) {
            remember(key, null, plugin.getNegativeTtlMs());
            return null;
        }
        if (status != 200) {
            throw new IOException("Mojang profile API 返回 HTTP " + status);
        }

        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        JsonElement props = root.get("properties");
        if (props != null && props.isJsonArray()) {
            for (JsonElement el : props.getAsJsonArray()) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject p = el.getAsJsonObject();
                if (p.has("name") && TEXTURES.equals(p.get("name").getAsString()) && p.has("value")) {
                    String value = p.get("value").getAsString();
                    String signature = p.has("signature") ? p.get("signature").getAsString() : null;
                    ProfileProperty prop = new ProfileProperty(TEXTURES, value, signature);
                    remember(key, prop, plugin.getPositiveTtlMs());
                    return prop;
                }
            }
        }
        remember(key, null, plugin.getNegativeTtlMs());
        return null;
    }

    private void remember(String key, ProfileProperty prop, long ttlMs) {
        if (cache.size() >= MAX_CACHE) {
            long now = System.currentTimeMillis();
            cache.entrySet().removeIf(e -> e.getValue().expired(now));
            if (cache.size() >= MAX_CACHE) {
                cache.clear();
            }
        }
        cache.put(key, new CacheEntry(prop, System.currentTimeMillis(), ttlMs));
    }

    private HttpResponse<String> sendGet(String url) throws IOException, InterruptedException {
        throttle();
        HttpResponse<String> resp = http.send(buildGet(url), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 429) {
            Thread.sleep(retryAfterMs(resp.headers().firstValue("Retry-After").orElse(null)));
            resp = http.send(buildGet(url), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        return resp;
    }

    private static long retryAfterMs(String value) {
        if (value == null) {
            return RETRY_DEFAULT_MS;
        }
        try {
            return Math.min(RETRY_CAP_MS, Math.max(1_000L, Long.parseLong(value.trim()) * 1000L));
        } catch (NumberFormatException e) {
            return RETRY_DEFAULT_MS;
        }
    }

    private HttpRequest buildGet(String url) {
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("仅支持 http/https 端点");
        }
        return HttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .header("User-Agent", "OfflineSkin/1.0.0")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
    }

    private void throttle() throws InterruptedException {
        long interval = plugin.getRequestIntervalMs();
        synchronized (rateLock) {
            long wait = lastRequestAt + interval - System.currentTimeMillis();
            if (wait > 0) {
                Thread.sleep(wait);
            }
            lastRequestAt = System.currentTimeMillis();
        }
    }

    public static String describeError(Throwable error) {
        while (error.getCause() != null) {
            error = error.getCause();
        }
        return error.getMessage() != null ? error.getMessage() : error.toString();
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
