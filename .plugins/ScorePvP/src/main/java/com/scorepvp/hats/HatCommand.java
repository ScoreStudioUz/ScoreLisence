package com.scorepvp.hats;

import com.scorepvp.ScorePvP;
import com.scorepvp.util.ColorUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * {@code /hats} opens the paginated hat menu. {@code /hats reload} (admin)
 * reloads hats.yml.
 */
public class HatCommand implements CommandExecutor, TabCompleter {

    private final ScorePvP plugin;

    public HatCommand(ScorePvP plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("scorepvp.hats.admin")) {
                sender.sendMessage(ColorUtil.color("&cRuxsat yo'q."));
                return true;
            }
            plugin.getHatManager().reload();
            sender.sendMessage(ColorUtil.color(plugin.getHatManager().msgReload()));
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        // If the RP gate is on and the player hasn't loaded it, tell them to wait.
        HatManager hats = plugin.getHatManager();
        if (hats.rpEnabled() && !hats.rpMandatory()
                && !plugin.getHatListener().hasLoadedRp(p.getUniqueId())) {
            p.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(ColorUtil.color(hats.rpNotLoaded())));
            return true;
        }
        // Don't open until owned-hats have loaded from the DB, otherwise a
        // just-relogged player could be charged again for a hat they own. If the
        // load somehow never completed (a dropped async read), re-trigger it here
        // so a still-online player isn't stuck on "loading" forever.
        if (!hats.isOwnedLoaded(p.getUniqueId())) {
            hats.loadOwnedAsync(p.getUniqueId());
            p.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(ColorUtil.color("&cMa'lumotlar yuklanmoqda, bir lahzadan so'ng urinib ko'ring.")));
            return true;
        }
        plugin.getHatGUI().open(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission("scorepvp.hats.admin")) return List.of("reload");
        return List.of();
    }
}
