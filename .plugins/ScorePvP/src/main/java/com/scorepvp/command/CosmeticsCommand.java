package com.scorepvp.command;

import com.scorepvp.ScorePvP;
import com.scorepvp.manager.CosmeticsManager;
import com.scorepvp.manager.CosmeticsManager.ArmorMaterial;
import com.scorepvp.manager.CosmeticsManager.ArmorPiece;
import com.scorepvp.util.ColorUtil;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * /cosmetics                                  → open the main menu.
 * /cosmetics admin shield add                 → store held shield (slot 0..35).
 * /cosmetics admin shield remove <slot>       → remove a stored shield.
 * /cosmetics admin armor set <piece>          → store held diamond/netherite piece.
 * /cosmetics admin armor remove <mat> <piece> → drop a stored armor piece.
 */
public class CosmeticsCommand implements CommandExecutor, TabCompleter {

    public static final String PERM_USE   = "duels.cosmetics";
    public static final String PERM_ADMIN = "duels.cosmetics.admin";

    private final ScorePvP plugin;

    public CosmeticsCommand(ScorePvP plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players.");
            return true;
        }

        if (args.length == 0) {
            if (!player.hasPermission(PERM_USE)) { tell(player, "messages.no-permission"); return true; }
            plugin.getCosmeticsGUI().openMain(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("admin")) {
            if (!player.hasPermission(PERM_ADMIN)) {
                tell(player, "messages.no-admin-permission");
                return true;
            }
            return handleAdmin(player, args);
        }

        if (!player.hasPermission(PERM_USE)) { tell(player, "messages.no-permission"); return true; }
        plugin.getCosmeticsGUI().openMain(player);
        return true;
    }

    private boolean handleAdmin(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ColorUtil.color("&e/cosmetics admin <shield|armor> ..."));
            return true;
        }

        String type = args[1].toLowerCase();
        CosmeticsManager mgr = plugin.getCosmeticsManager();

        if (type.equals("shield")) {
            if (args.length < 3) {
                player.sendMessage(ColorUtil.color("&e/cosmetics admin shield <add|remove>"));
                return true;
            }
            String action = args[2].toLowerCase();
            if (action.equals("add")) {
                ItemStack held = player.getInventory().getItemInMainHand();
                if (held == null || held.getType() != Material.SHIELD) {
                    tell(player, "messages.not-holding-shield");
                    return true;
                }
                int slot = mgr.addShield(held);
                if (slot < 0) { tell(player, "messages.shield-slots-full"); return true; }
                tellPlaceholder(player, "messages.shield-added", "{slot}", String.valueOf(slot));
                return true;
            }
            if (action.equals("remove")) {
                if (args.length < 4) {
                    player.sendMessage(ColorUtil.color("&e/cosmetics admin shield remove <slot>"));
                    return true;
                }
                int slot;
                try { slot = Integer.parseInt(args[3]); }
                catch (NumberFormatException ex) { tell(player, "messages.invalid-slot"); return true; }
                if (!mgr.removeShield(slot)) { tell(player, "messages.invalid-slot"); return true; }
                tellPlaceholder(player, "messages.shield-removed", "{slot}", String.valueOf(slot));
                return true;
            }
            return true;
        }

        if (type.equals("armor")) {
            if (args.length < 3) {
                player.sendMessage(ColorUtil.color("&e/cosmetics admin armor <set|remove> ..."));
                return true;
            }
            String action = args[2].toLowerCase();

            if (action.equals("set")) {
                if (args.length < 4) {
                    player.sendMessage(ColorUtil.color("&e/cosmetics admin armor set <helmet|chestplate|leggings|boots>"));
                    return true;
                }
                ArmorPiece piece = parsePiece(args[3]);
                if (piece == null) { tell(player, "messages.invalid-piece"); return true; }
                ItemStack held = player.getInventory().getItemInMainHand();
                ArmorMaterial mat = detectArmorMaterial(held, piece);
                if (mat == null) {
                    String pieceLabel = pieceLabel(piece);
                    tellPlaceholder(player, "messages.not-holding-armor", "{piece}", pieceLabel);
                    return true;
                }
                mgr.setArmorPiece(mat, piece, held);
                tellPlaceholder(player, "messages.armor-set",
                        "{piece}", pieceLabel(piece));
                return true;
            }

            if (action.equals("remove")) {
                if (args.length < 5) {
                    player.sendMessage(ColorUtil.color("&e/cosmetics admin armor remove <diamond|netherite> <piece>"));
                    return true;
                }
                ArmorMaterial mat;
                try { mat = ArmorMaterial.valueOf(args[3].toUpperCase()); }
                catch (IllegalArgumentException ex) {
                    tell(player, "messages.invalid-armor-material");
                    return true;
                }
                ArmorPiece piece = parsePiece(args[4]);
                if (piece == null) { tell(player, "messages.invalid-piece"); return true; }
                if (!mgr.removeArmorPiece(mat, piece)) {
                    tell(player, "messages.invalid-piece");
                    return true;
                }
                tellPlaceholder(player, "messages.armor-set", "{piece}", pieceLabel(piece));
                return true;
            }
            return true;
        }

        player.sendMessage(ColorUtil.color("&e/cosmetics admin <shield|armor>"));
        return true;
    }

    /**
     * Maps a held item to its {@link ArmorMaterial}. Returns null when the
     * material does not match the expected piece (e.g. holding leggings while
     * trying to register a helmet). Diamond/netherite armor types only.
     */
    private ArmorMaterial detectArmorMaterial(ItemStack held, ArmorPiece piece) {
        if (held == null || held.getType().isAir()) return null;
        Material m = held.getType();
        return switch (piece) {
            case HELMET -> m == Material.DIAMOND_HELMET ? ArmorMaterial.DIAMOND
                          : m == Material.NETHERITE_HELMET ? ArmorMaterial.NETHERITE : null;
            case CHESTPLATE -> m == Material.DIAMOND_CHESTPLATE ? ArmorMaterial.DIAMOND
                              : m == Material.NETHERITE_CHESTPLATE ? ArmorMaterial.NETHERITE : null;
            case LEGGINGS -> m == Material.DIAMOND_LEGGINGS ? ArmorMaterial.DIAMOND
                            : m == Material.NETHERITE_LEGGINGS ? ArmorMaterial.NETHERITE : null;
            case BOOTS -> m == Material.DIAMOND_BOOTS ? ArmorMaterial.DIAMOND
                         : m == Material.NETHERITE_BOOTS ? ArmorMaterial.NETHERITE : null;
        };
    }

    private ArmorPiece parsePiece(String s) {
        try { return ArmorPiece.valueOf(s.toUpperCase()); }
        catch (IllegalArgumentException ex) { return null; }
    }

    private String pieceLabel(ArmorPiece piece) {
        return plugin.getConfigManager().cosStr("piece-names." + piece.name().toLowerCase());
    }

    // ── Chat helpers ─────────────────────────────────────────────────────────

    private void tell(Player p, String key) {
        String prefix = plugin.getConfigManager().cosStr("messages.prefix");
        String body   = plugin.getConfigManager().cosStr(key);
        p.sendMessage(ColorUtil.color(prefix + body));
    }

    private void tellPlaceholder(Player p, String key, String ph, String value) {
        String prefix = plugin.getConfigManager().cosStr("messages.prefix");
        String body   = plugin.getConfigManager().cosStr(key).replace(ph, value);
        p.sendMessage(ColorUtil.color(prefix + body));
    }

    // ── Tab completion ───────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                       @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player p)) return Collections.emptyList();
        if (args.length == 1) {
            if (p.hasPermission(PERM_ADMIN)) return filter(args[0], "admin");
            return Collections.emptyList();
        }
        if (!p.hasPermission(PERM_ADMIN)) return Collections.emptyList();
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return filter(args[1], "shield", "armor");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin")) {
            if (args[1].equalsIgnoreCase("shield")) return filter(args[2], "add", "remove");
            if (args[1].equalsIgnoreCase("armor"))  return filter(args[2], "set", "remove");
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("admin")) {
            if (args[1].equalsIgnoreCase("shield") && args[2].equalsIgnoreCase("remove")) {
                List<String> out = new ArrayList<>();
                for (int s : plugin.getCosmeticsManager().getShields().keySet()) out.add(String.valueOf(s));
                return filter(args[3], out.toArray(new String[0]));
            }
            if (args[1].equalsIgnoreCase("armor") && args[2].equalsIgnoreCase("set")) {
                return filter(args[3], "helmet", "chestplate", "leggings", "boots");
            }
            if (args[1].equalsIgnoreCase("armor") && args[2].equalsIgnoreCase("remove")) {
                return filter(args[3], "diamond", "netherite");
            }
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("admin")
                && args[1].equalsIgnoreCase("armor") && args[2].equalsIgnoreCase("remove")) {
            return filter(args[4], "helmet", "chestplate", "leggings", "boots");
        }
        return Collections.emptyList();
    }

    private List<String> filter(String prefix, String... opts) {
        List<String> out = new ArrayList<>();
        for (String o : opts) if (o.toLowerCase().startsWith(prefix.toLowerCase())) out.add(o);
        return out;
    }
}
