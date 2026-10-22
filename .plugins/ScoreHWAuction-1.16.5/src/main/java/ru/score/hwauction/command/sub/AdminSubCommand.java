package ru.score.hwauction.command.sub;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.model.AuctionItem;
import ru.score.hwauction.model.ItemTemplate;
import ru.score.hwauction.util.ItemUtil;

import java.util.List;
import java.util.logging.Level;

public final class AdminSubCommand {

    private final ScoreHWAuction plugin;

    public AdminSubCommand(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    public void execute(Player player, String[] args) {
        if (!player.hasPermission("scoreah.admin")) {
            player.sendMessage(plugin.getMessages().get("no-permission"));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(plugin.getMessages().get("admin-usage"));
            return;
        }
        switch (args[1].toLowerCase()) {
            case "remove"    -> handleRemove(player, args);
            case "clear"     -> handleClear(player, args);
            case "info"      -> handleInfo(player, args);
            case "add"       -> handleTemplateAdd(player, args);
            case "templates" -> handleTemplateList(player);
            case "deltemplate" -> handleTemplateRemove(player, args);
            default          -> player.sendMessage(plugin.getMessages().get("admin-usage"));
        }
    }

    // ── /ah admin add <category> [name] [matchmode] ──────────────────────────
    private void handleTemplateAdd(Player admin, String[] args) {
        if (args.length < 3) {
            admin.sendMessage(plugin.getMessages().get("template-add-usage"));
            return;
        }
        ItemStack hand = admin.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            admin.sendMessage(plugin.getMessages().get("template-no-item"));
            return;
        }

        String category = args[2].toLowerCase();
        if (!plugin.getCategoryRegistry().exists(category)) {
            admin.sendMessage(plugin.getMessages().get("template-bad-category", "{category}", category));
            return;
        }

        // Optional custom search name (defaults to the item's current display/vanilla name).
        // Optional trailing match-mode token: EXACT | MATERIAL_ENCHANTS | MATERIAL_LORE.
        ItemTemplate.MatchMode mode = ItemTemplate.MatchMode.MATERIAL_ENCHANTS;
        int nameEnd = args.length;
        if (args.length >= 4) {
            String last = args[args.length - 1].toUpperCase(java.util.Locale.ROOT);
            try {
                mode = ItemTemplate.MatchMode.valueOf(last);
                nameEnd = args.length - 1;   // consume last token as the mode
            } catch (IllegalArgumentException ignored) { /* last token is part of the name */ }
        }

        String searchName;
        if (nameEnd > 3) {
            searchName = String.join(" ", java.util.Arrays.copyOfRange(args, 3, nameEnd)).trim();
        } else {
            searchName = ItemUtil.getDisplayName(hand);
        }
        if (searchName.isBlank()) searchName = ItemUtil.getDisplayName(hand);

        String id = generateId(searchName);
        ItemTemplate template = new ItemTemplate(id, searchName, category, mode, hand);
        plugin.getTemplateRegistry().add(template);

        admin.sendMessage(plugin.getMessages().get("template-added",
                "{name}", searchName, "{category}", category, "{mode}", mode.name(), "{id}", id));

        // Warn if this template will match every plain item of its material.
        boolean noEnchants = hand.getEnchantments().isEmpty()
                && !(hand.getItemMeta() instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta e
                        && !e.getStoredEnchants().isEmpty());
        boolean noLore = !hand.hasItemMeta() || hand.getItemMeta() == null
                || !hand.getItemMeta().hasLore();
        if ((mode == ItemTemplate.MatchMode.MATERIAL_ENCHANTS && noEnchants)
                || (mode == ItemTemplate.MatchMode.MATERIAL_LORE && noLore)
                || (mode == ItemTemplate.MatchMode.EXACT && noEnchants && noLore)) {
            admin.sendMessage(plugin.getMessages().get("template-broad-warning",
                    "{material}", hand.getType().name()));
        }
    }

    private void handleTemplateList(Player admin) {
        List<ItemTemplate> all = plugin.getTemplateRegistry().all();
        if (all.isEmpty()) {
            admin.sendMessage(plugin.getMessages().get("template-list-empty"));
            return;
        }
        admin.sendMessage(plugin.getMessages().get("template-list-header",
                "{count}", String.valueOf(all.size())));
        for (ItemTemplate t : all) {
            admin.sendMessage(plugin.getMessages().raw("template-list-entry",
                    "{id}", t.getId(), "{name}", t.getSearchName(),
                    "{category}", t.getCategory(), "{mode}", t.getMatchMode().name()));
        }
    }

    private void handleTemplateRemove(Player admin, String[] args) {
        if (args.length < 3) {
            admin.sendMessage(plugin.getMessages().get("template-del-usage"));
            return;
        }
        String id = args[2];
        boolean ok = plugin.getTemplateRegistry().remove(id);
        if (ok) admin.sendMessage(plugin.getMessages().get("template-removed", "{id}", id));
        else    admin.sendMessage(plugin.getMessages().get("template-not-found", "{id}", id));
    }

    /** Build a stable, unique, filesystem/YAML-safe id from a display name. */
    private String generateId(String name) {
        String base = ru.score.hwauction.util.ColorUtil.strip(name)
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9а-яё]+", "_")
                .replaceAll("^_+|_+$", "");
        if (base.isBlank()) base = "template";
        String id = base;
        int n = 1;
        while (plugin.getTemplateRegistry().exists(id)) {
            id = base + "_" + (++n);
        }
        return id;
    }

    private void handleRemove(Player admin, String[] args) {
        if (args.length < 3) {
            admin.sendMessage(plugin.getMessages().get("admin-usage")); return;
        }
        int id;
        try { id = Integer.parseInt(args[2]); }
        catch (NumberFormatException e) {
            admin.sendMessage(plugin.getMessages().get("invalid-id")); return;
        }
        boolean ok = plugin.getListingService().cancel(admin, id);
        if (ok) admin.sendMessage(plugin.getMessages().get("admin-removed", "{id}", String.valueOf(id)));
        else    admin.sendMessage(plugin.getMessages().get("item-already-sold"));
    }

    private void handleClear(Player admin, String[] args) {
        if (args.length < 3) {
            // Clear all expired
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    plugin.getDb().markExpiredItems();
                    Bukkit.getScheduler().runTask(plugin,
                            () -> admin.sendMessage(plugin.getMessages().get("admin-cleared-expired")));
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Error clearing expired", e);
                }
            });
            return;
        }
        // /ah admin clear <player>
        String targetName = args[2];
        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var items = plugin.getDb().loadItemsByPlayer(
                        target.getUniqueId(), ru.score.hwauction.model.AuctionStatus.ACTIVE);
                for (AuctionItem item : items) {
                    item.setStatus(ru.score.hwauction.model.AuctionStatus.CANCELLED);
                    plugin.getDb().updateItem(item);
                    plugin.getAuctionCache().remove(item.getId());
                }
                int count = items.size();
                Bukkit.getScheduler().runTask(plugin,
                        () -> admin.sendMessage(plugin.getMessages().get("admin-cleared-player",
                                "{player}", targetName, "{count}", String.valueOf(count))));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error clearing player listings", e);
            }
        });
    }

    private void handleInfo(Player admin, String[] args) {
        int total = plugin.getAuctionCache().size();
        admin.sendMessage(plugin.getMessages().get("admin-info-cache", "{count}", String.valueOf(total)));
    }
}
