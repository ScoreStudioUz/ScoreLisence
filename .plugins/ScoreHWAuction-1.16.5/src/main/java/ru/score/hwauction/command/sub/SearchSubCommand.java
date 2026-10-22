package ru.score.hwauction.command.sub;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.model.PlayerSession;
import ru.score.hwauction.util.ItemUtil;

import java.util.Arrays;

public final class SearchSubCommand {

    private final ScoreHWAuction plugin;

    public SearchSubCommand(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    public void execute(Player player, String[] args) {
        if (!player.hasPermission("scoreah.search")) {
            player.sendMessage(plugin.getMessages().get("no-permission"));
            return;
        }

        String query;
        if (args.length < 2) {
            // No text query — search by the item held in hand.
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
                player.sendMessage(plugin.getMessages().get("search-usage"));
                return;
            }
            query = searchNameFor(hand);
        } else {
            query = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        }

        PlayerSession session = plugin.getSessionManager().getOrCreate(player);
        session.setSearchQuery(query);
        session.setPlayerFilter(null);
        player.sendMessage(plugin.getMessages().get("search-applied", "{query}", query));
        plugin.getMainGui().open(player);
    }

    /**
     * Best search term for a held item: an admin template's original name if it matches,
     * otherwise the item's Russian vanilla name, else the English material name.
     * A renamed vanilla item is searched by its type, not its custom name, so results
     * aren't limited to that one player's rename.
     */
    private String searchNameFor(ItemStack hand) {
        var tmpl = plugin.getTemplateRegistry().findMatching(hand);
        if (tmpl.isPresent()) return tmpl.get().getSearchName();
        String ru = plugin.getRuNameProvider().getRussian(hand.getType());
        if (!ru.isEmpty()) return ru;
        return ItemUtil.materialName(hand.getType());
    }
}
