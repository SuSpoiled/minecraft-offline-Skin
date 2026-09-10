package com.offlineskin;

import com.destroystokyo.paper.profile.ProfileProperty;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

public final class SkinCommand implements TabExecutor {

    private final OfflineSkinPlugin plugin;

    public SkinCommand(OfflineSkinPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendRichMessage("<red>此命令只能由玩家使用。");
            return true;
        }

        if (args.length == 0) {
            player.sendRichMessage("<gold>/skin &lt;名字&gt; <gray>- 应用正版账户 <gold>&lt;名字&gt; 的皮肤");
            player.sendRichMessage("<gold>/skin clear <gray>- 重置你的皮肤");
            return true;
        }

        if (args[0].equalsIgnoreCase("clear")) {
            if (!player.hasPermission("offlineskin.clear")) {
                player.sendRichMessage("<red>你没有权限执行此操作。");
                return true;
            }
            SkinListener.clearTextures(player);
            player.sendRichMessage("<green>你的皮肤已重置。");
            return true;
        }

        if (!player.hasPermission("offlineskin.apply")) {
            player.sendRichMessage("<red>你没有权限执行此操作。");
            return true;
        }

        String target = args[0];
        if (!MojangNames.isValid(target)) {
            player.sendRichMessage("<red>无效的游戏名（3-16 位：a-z、0-9、_）。");
            return true;
        }

        player.sendRichMessage("<gray>正在查询 <gold>" + target + "<gray> 的皮肤...");
        plugin.getSkinService().lookupAsync(target).whenComplete((textures, error) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (error != null) {
                    player.sendRichMessage("<red>查询皮肤失败：" + SkinService.describeError(error));
                    return;
                }
                if (textures == null) {
                    player.sendRichMessage("<red>未找到名为 <gold>" + target + "<red> 的正版账户。");
                    return;
                }
                SkinListener.applyTextures(player, textures);
                player.sendRichMessage("<green>已应用 <gold>" + target + "<green> 的皮肤。");
            });
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase();
        List<String> result = new ArrayList<>();
        if ("clear".startsWith(prefix)) {
            result.add("clear");
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            String name = player.getName();
            if (name.toLowerCase().startsWith(prefix)) {
                result.add(name);
            }
        }
        return result;
    }
}
