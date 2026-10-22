package ru.score.hwauction.manager;

import org.bukkit.Material;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.config.CategoryRegistry;
import ru.score.hwauction.model.AuctionItem;
import ru.score.hwauction.model.ItemTemplate;
import ru.score.hwauction.model.PlayerSession;
import ru.score.hwauction.model.SortType;
import ru.score.hwauction.util.ItemUtil;
import ru.score.hwauction.util.SkullUtil;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public final class FilterService {

    private final ScoreHWAuction plugin;
    private final CategoryRegistry categoryRegistry;

    public FilterService(ScoreHWAuction plugin) {
        this.plugin = plugin;
        this.categoryRegistry = plugin.getCategoryRegistry();
    }

    public List<AuctionItem> filter(List<AuctionItem> source, PlayerSession session) {
        return source.stream()
                .filter(i -> matchesMode(i, session))
                .filter(i -> matchesCategory(i, session))
                .filter(i -> matchesSearch(i, session))
                .filter(i -> matchesPlayer(i, session))
                .sorted(comparatorFor(session.getSortType()))
                .collect(Collectors.toList());
    }

    private boolean matchesMode(AuctionItem item, PlayerSession session) {
        return item.getMode() == session.getViewMode();
    }

    private boolean matchesCategory(AuctionItem item, PlayerSession session) {
        String cat = session.getCategoryFilter();
        if (cat == null || cat.equalsIgnoreCase("all")) return true;

        // Always match via ItemStack — this enables true multi-category browsing.
        // An item appears in every category whose rules it satisfies, regardless of
        // which category was stored in the DB at listing time.
        if (item.getItemStack() != null) {
            return categoryRegistry.itemMatchesFilter(item.getItemStack(), cat);
        }

        // Fallback if ItemStack unavailable (should not happen in normal usage)
        return cat.equalsIgnoreCase(item.getCategory());
    }

    private boolean matchesSearch(AuctionItem item, PlayerSession session) {
        if (!session.hasSearch()) return true;
        if (item.getItemStack() == null) return false;
        String raw = session.getSearchQuery().trim();
        boolean exact = raw.startsWith("\"") && raw.endsWith("\"") && raw.length() > 2;
        String query = exact ? raw.substring(1, raw.length() - 1).toLowerCase() : raw.toLowerCase();

        String displayName  = ItemUtil.getDisplayName(item.getItemStack()).toLowerCase();
        String materialName = item.getItemStack().getType().name().toLowerCase().replace("_", " ");
        String russianName  = plugin.getRuNameProvider().getRussian(item.getItemStack().getType()).toLowerCase();

        // Admin template: search by the pinned original name, immune to renaming.
        Optional<ItemTemplate> tmpl = plugin.getTemplateRegistry().findMatching(item.getItemStack());
        String templateName = tmpl.map(t -> t.getSearchName().toLowerCase()).orElse("");

        if (exact) {
            return displayName.equals(query) || materialName.equals(query)
                    || (!russianName.isEmpty() && russianName.equals(query))
                    || (!templateName.isEmpty() && templateName.equals(query));
        }
        if (displayName.contains(query) || materialName.contains(query)) return true;
        if (!russianName.isEmpty() && russianName.contains(query)) return true;
        if (!templateName.isEmpty() && templateName.contains(query)) return true;

        if (item.getItemStack().getType() == Material.PLAYER_HEAD) {
            if (SkullUtil.getSkullDisplayName(item.getItemStack()).toLowerCase().contains(query)) return true;
        }
        if (item.getItemStack().hasItemMeta() && item.getItemStack().getItemMeta() != null
                && item.getItemStack().getItemMeta().hasLore()) {
            for (String line : item.getItemStack().getItemMeta().getLore()) {
                if (line != null && line.toLowerCase().contains(query)) return true;
            }
        }
        return false;
    }

    private boolean matchesPlayer(AuctionItem item, PlayerSession session) {
        if (!session.hasPlayerFilter()) return true;
        return item.getSellerName().equalsIgnoreCase(session.getPlayerFilter());
    }

    private Comparator<AuctionItem> comparatorFor(SortType sort) {
        Comparator<AuctionItem> byNewest = Comparator.comparingLong(AuctionItem::getListedAt).reversed();
        return switch (sort) {
            case NEWEST        -> byNewest;
            case OLDEST        -> Comparator.comparingLong(AuctionItem::getListedAt);
            case CHEAPEST      -> Comparator.comparingDouble(AuctionItem::getPrice).thenComparing(byNewest);
            case EXPENSIVE     -> Comparator.comparingDouble(AuctionItem::getPrice).reversed().thenComparing(byNewest);
            case CHEAPEST_PER  -> Comparator.comparingDouble(AuctionItem::getPricePerUnit).thenComparing(byNewest);
            case EXPENSIVE_PER -> Comparator.comparingDouble(AuctionItem::getPricePerUnit).reversed().thenComparing(byNewest);
        };
    }
}
