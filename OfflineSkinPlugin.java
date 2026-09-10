package com.offlineskin;

import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class OfflineSkinPlugin extends JavaPlugin {

    private SkinService skinService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.skinService = new SkinService(this);

        SkinListener listener = new SkinListener(this);
        getServer().getPluginManager().registerEvents(listener, this);

        PluginCommand skinCommand = getCommand("skin");
        if (skinCommand != null) {
            SkinCommand executor = new SkinCommand(this);
            skinCommand.setExecutor(executor);
            skinCommand.setTabCompleter(executor);
        }

        for (Player player : getServer().getOnlinePlayers()) {
            listener.applyIfMatching(player);
        }

        getLogger().info("OfflineSkin 已启用");
    }

    @Override
    public void onDisable() {
        if (skinService != null) {
            skinService.shutdown();
        }
    }

    public SkinService getSkinService() {
        return skinService;
    }

    public boolean autoApply() {
        return getConfig().getBoolean("auto-apply", true);
    }

    public long getPositiveTtlMs() {
        return getConfig().getLong("cache-ttl-minutes", 60L) * 60_000L;
    }

    public long getNegativeTtlMs() {
        return getConfig().getLong("negative-cache-ttl-minutes", 1440L) * 60_000L;
    }

    public long getRequestIntervalMs() {
        return Math.max(100L, getConfig().getLong("request-interval-ms", 1000L));
    }

    public String getNameApiUrl() {
        return url("name-api-url", SkinService.DEFAULT_NAME_API);
    }

    public String getFallbackNameApiUrl() {
        return url("fallback-name-api-url", SkinService.DEFAULT_FALLBACK_NAME_API);
    }

    public String getProfileApiUrl() {
        return url("profile-api-url", SkinService.DEFAULT_PROFILE_API);
    }

    private String url(String key, String def) {
        String v = getConfig().getString(key, def);
        return v == null || v.isBlank() ? def : v;
    }
}
