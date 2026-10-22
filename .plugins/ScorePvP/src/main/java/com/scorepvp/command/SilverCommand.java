package com.scorepvp.command;

import com.scorepvp.ScorePvP;
import com.scorepvp.manager.TierManager;
import com.scorepvp.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * /kumush                       → your own balance (players).
 * /kumush look <nick>           → view a player's balance (admin).
 * /kumush give <nick> <amount>  → add silver (admin, works offline).
 * /kumush set  <nick> <amount>  → set silver (admin, works offline).
 * /kumush remove <nick> <amount>→ subtract silver (admin, works offline).
 *
 * Offline targets are resolved by nick via {@link TierManager#resolveTarget}.
 */
public class SilverCommand implements CommandExecutor, TabCompleter {

    public static final String PERM_ADMIN = "scorepvp.kumush.admin";

    private final ScorePvP plugin;

    public SilverCommand(ScorePvP plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        // No args → own balance.
        if (args.length == 0) {
            if (!(sender instanceof Player p)) { msg(sender, "kumush.players-only"); return true; }
            msgRaw(p, plugin.getConfigManager().rawMsg("kumush.balance",
                    "{amount}", String.valueOf(plugin.getSilverManager().get(p.getUniqueId()))));
            return true;
        }

        String sub = args[0].toLowerCase();
        if (!sender.hasPermission(PERM_ADMIN)) { msg(sender, "kumush.no-permission"); return true; }

        switch (sub) {
            case "look" -> {
                if (args.length < 2) { msg(sender, "kumush.usage-look"); return true; }
                TierManager.Resolved t = plugin.getTierManager().resolveTarget(args[1]);
                if (t == null) { msgPh(sender, "kumush.player-not-found", "{player}", args[1]); return true; }
                plugin.getSilverManager().getOffline(t.uuid(), bal ->
                        msgRaw(sender, plugin.getConfigManager().rawMsg("kumush.look",
                                "{player}", t.name(), "{amount}", String.valueOf(bal))));
                return true;
            }
            case "give", "set", "remove" -> {
                if (args.length < 3) { msgPh(sender, "kumush.usage-amount", "{sub}", sub); return true; }
                TierManager.Resolved t = plugin.getTierManager().resolveTarget(args[1]);
                if (t == null) { msgPh(sender, "kumush.player-not-found", "{player}", args[1]); return true; }
                int amount;
                try { amount = Integer.parseInt(args[2]); }
                catch (NumberFormatException ex) { msg(sender, "kumush.invalid-amount"); return true; }
                if (amount < 0) { msg(sender, "kumush.invalid-amount"); return true; }

                java.util.function.IntUnaryOperator op = switch (sub) {
                    case "give"   -> cur -> cur + amount;
                    case "remove" -> cur -> cur - amount;
                    default       -> cur -> amount; // set
                };
                String key = "kumush." + sub + "-done";
                plugin.getSilverManager().mutateOffline(t.uuid(), op, newBal ->
                        msgRaw(sender, plugin.getConfigManager().rawMsg(key,
                                "{player}", t.name(),
                                "{amount}", String.valueOf(amount),
                                "{total}", String.valueOf(newBal))));
                return true;
            }
            default -> { msg(sender, "kumush.usage"); return true; }
        }
    }

    private void msg(CommandSender s, String key) {
        s.sendMessage(plugin.getConfigManager().rawMsg(key));
    }
    private void msgPh(CommandSender s, String key, String ph, String v) {
        s.sendMessage(plugin.getConfigManager().rawMsg(key, ph, v));
    }
    private void msgRaw(CommandSender s, String colored) {
        s.sendMessage(colored);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) return List.of();
        if (args.length == 1) return filter(args[0], "give", "set", "remove", "look");
        if (args.length == 2) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(args[1], names.toArray(new String[0]));
        }
        return List.of();
    }

    private List<String> filter(String prefix, String... opts) {
        List<String> out = new ArrayList<>();
        for (String o : opts) if (o.toLowerCase().startsWith(prefix.toLowerCase())) out.add(o);
        return out;
    }
}
