package com.offlineskin;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.entity.Player;

public final class SkinListener implements Listener {

    private final OfflineSkinPlugin plugin;

    public SkinListener(OfflineSkinPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        applyIfMatching(event.getPlayer());
    }

    public void applyIfMatching(Player player) {
        if (plugin.getServer().getOnlineMode() || !plugin.autoApply()) {
            return;
        }
        String name = player.getName();
        if (!MojangNames.isValid(name)) {
            return;
        }

        plugin.getSkinService().lookupAsync(name).whenComplete((textures, error) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            if (error != null) {
                plugin.getLogger().warning("无法解析 '" + name + "' 的 Mojang 皮肤：" + SkinService.describeError(error));
                return;
            }
            if (textures == null) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    applyTextures(player, textures);
                    plugin.getLogger().info("已为 '" + name + "' 应用正版皮肤");
                }
            });
        });
    }

    public static void applyTextures(Player player, ProfileProperty textures) {
        PlayerProfile profile = player.getPlayerProfile();
        profile.setProperty(textures);
        player.setPlayerProfile(profile);
        player.sendHealthUpdate();
    }

    public static void clearTextures(Player player) {
        PlayerProfile profile = player.getPlayerProfile();
        profile.removeProperty("textures");
        player.setPlayerProfile(profile);
    }
}
