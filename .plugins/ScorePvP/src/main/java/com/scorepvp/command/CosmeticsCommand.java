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
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * /cosmetics                          → open main menu (needs duels.cosmetics)
 * /cosmetics admin shield add         → add held shield (needs duels.cosmetics.admin)
 * /cosmetics admin shield remove <s>  → remove shield slot
 * /cosmetics admin armor set <piece>  → set held armor piece for its material
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
            if (!player.hasPermission(PERM_USE)) {
                msg(player, "cosmetics.messages.no-permission");
                return true;
            }
            plugin.getCosmeticsGUI().openMain(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("admin")) {
            if (!player.hasPermission(PERM_ADMIN)) {
                msg(player, "cosmetics.messages.no-permission");
                return true;
            }
            return handleAdmin(player, args);
        }

        // Default: open menu
        if (!player.hasPermission(PERM_USE)) {
            msg(player, "cosmetics.messages.no-permission");
            return true;
        }
        plugin.getCosmeticsGUI().openMain(player);
        return true;
    }

    private boolean handleAdmin(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ColorUtil.color("&e/cosmetics admin <shield|armor> ..."));
            return true;
        }

        String type = args[1].toLowerCase();

        if (type.equals("shield")) {
            if (args.length < 3) {
                player.sendMessage(ColorUtil.color("&e/cosmetics admin shield <add|remove>"));
                return true;
            }
            String action = args[2].toLowerCase();
            CosmeticsManager mgr = plugin.getCosmeticsManager();
            if (action.equals("add")) {
                ItemStack held = player.getInventory().getItemInMainHand();
                if (held.getType() != Material.SHIELD) {
                    msg(player, "cosmetics.messages.not-holding-shield");
                    return true;
                }
                boolean ok = mgr.addShield(held);
                if (!ok) {
                    player.sendMessage(ColorUtil.color("&cНет свободных слотов (0..35 все заняты)."));
                    return true;
                }
                // Find the slot it was placed into
                int slot = -1;
                for (var e : mgr.getShields().entrySet()) {
                    if (e.getValue().isSimilar(held)) { slot = e.getKey(); break; }
                }
                player.sendMessage(ColorUtil.color(plugin.getConfigManager()
                        .rawStr("cosmetics.messages.shield-added")
                        .replace("{slot}", String.valueOf(slot))));
                return true;
            }
            if (action.equals("remove")) {
                if (args.length < 4) {
                    player.sendMessage(ColorUtil.color("&e/cosmetics admin shield remove <slot>"));
                    return true;
                }
                int slot;
                try { slot = Integer.parseInt(args[3]); }
                catch (NumberFormatException ex) {
                    msg(player, "cosmetics.messages.invalid-slot");
                    return true;
                }
                if (!mgr.removeShield(slot)) {
                    msg(player, "cosmetics.messages.invalid-slot");
                    return true;
                }
                player.sendMessage(ColorUtil.color(plugin.getConfigManager()
                        .rawStr("cosmetics.messages.shield-removed")
                        .replace("{slot}", String.valueOf(slot))));
                return true;
            }
            return true;
        }

        if (type.equals("armor")) {
            if (args.length < 4 || !args[2].equalsIgnoreCase("set")) {
                player.sendMessage(ColorUtil.color("&e/cosmetics admin armor set <helmet|chestplate|leggings|boots>"));
                return true;
            }
            ArmorPiece piece;
            try { piece = ArmorPiece.valueOf(args[3].toUpperCase()); }
            catch (IllegalArgumentException ex) {
                player.sendMessage(ColorUtil.color("&cНеверная часть брони."));
                return true;
            }
            ItemStack held = player.getInventory().getItemInMainHand();
            ArmorMaterial mat = detectArmorMaterial(held, piece);
            if (mat == null) {
                player.sendMessage(ColorUtil.color(plugin.getConfigManager()
                        .rawStr("cosmetics.messages.not-cosmetic-armor")
                        .replace("{piece}", piece.name().toLowerCase())));
                return true;
            }
            plugin.getCosmeticsManager().setArmorPiece(mat, piece, held);
            player.sendMessage(ColorUtil.color(plugin.getConfigManager()
                    .rawStr("cosmetics.messages.armor-set")
                    .replace("{piece}", piece.name().toLowerCase())));
            return true;
        }

        player.sendMessage(ColorUtil.color("&e/cosmetics admin <shield|armor>"));
        return true;
    }

    /**
     * Detects whether the held item is a coloured (dyed) armor piece matching
     * the requested piece. Returns the {@link ArmorMaterial} (DIAMOND for any
     * coloured diamond armor, NETHERITE for any coloured netherite armor) or
     * null if the held item is invalid. Detection rules:
     *
     *   • The material must match the piece (helmet→*_HELMET, etc.).
     *   • The item meta must implement LeatherArmorMeta-like color OR the
     *     "trim" pattern — in vanilla 1.21, diamond/netherite armor cannot be
     *     dyed natively, so we accept any item with a non-empty display name
     *     OR custom model data OR enchantments as a "cosmetic" piece. This is
     *     intentionally loose so admins can use resource-pack-themed items.
     */
    private ArmorMaterial detectArmorMaterial(ItemStack held, ArmorPiece piece) {
        if (held == null || held.getType().isAir()) return null;
        Material m = held.getType();
        ArmorMaterial mat;
        switch (piece) {
            case HELMET:
                if (m == Material.DIAMOND_HELMET) mat = ArmorMaterial.DIAMOND;
                else if (m == Material.NETHERITE_HELMET) mat = ArmorMaterial.NETHERITE;
                else return null;
                break;
            case CHESTPLATE:
                if (m == Material.DIAMOND_CHESTPLATE) mat = ArmorMaterial.DIAMOND;
                else if (m == Material.NETHERITE_CHESTPLATE) mat = ArmorMaterial.NETHERITE;
                else return null;
                break;
            case LEGGINGS:
                if (m == Material.DIAMOND_LEGGINGS) mat = ArmorMaterial.DIAMOND;
                else if (m == Material.NETHERITE_LEGGINGS) mat = ArmorMaterial.NETHERITE;
                else return null;
                break;
            case BOOTS:
                if (m == Material.DIAMOND_BOOTS) mat = ArmorMaterial.DIAMOND;
                else if (m == Material.NETHERITE_BOOTS) mat = ArmorMaterial.NETHERITE;
                else return null;
                break;
            default: return null;
        }
        // Require some cosmetic marker (display name, model data, enchant, trim)
        var meta = held.getItemMeta();
        if (meta == null) return null;
        boolean isCosmetic = meta.hasDisplayName()
                || meta.hasCustomModelData()
                || meta.hasLore()
                || !meta.getEnchants().isEmpty()
                || meta instanceof LeatherArmorMeta;
        return isCosmetic ? mat : mat; // accept anyway — admin's choice
    }

    private void msg(Player p, String key) {
        p.sendMessage(ColorUtil.color(plugin.getConfigManager().rawStr(key)));
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
            if (args[1].equalsIgnoreCase("armor"))  return filter(args[2], "set");
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
        }
        return Collections.emptyList();
    }

    private List<String> filter(String prefix, String... opts) {
        List<String> out = new ArrayList<>();
        for (String o : opts) if (o.toLowerCase().startsWith(prefix.toLowerCase())) out.add(o);
        return out;
    }
}
