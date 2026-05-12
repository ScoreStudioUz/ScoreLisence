package ru.score.hwauction.gui;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.score.hwauction.util.ColorUtil;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class GuiUtil {

    private static final ItemStack FILLER = buildFiller();

    private GuiUtil() {}

    public static ItemStack build(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta  = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(ColorUtil.translate(name));
        if (lore != null && !lore.isEmpty()) {
            meta.setLore(lore.stream().map(ColorUtil::translate).collect(Collectors.toList()));
        }
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack build(Material mat, String name, String... loreLines) {
        return build(mat, name, Arrays.asList(loreLines));
    }

    public static ItemStack build(Material mat, String name) {
        return build(mat, name, (List<String>) null);
    }

    public static ItemStack filler() {
        return FILLER.clone();
    }

    private static ItemStack buildFiller() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta  = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Fill all null/air slots in an inventory with the filler pane */
    public static void fillEmpty(org.bukkit.inventory.Inventory inv) {
        ItemStack f = filler();
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, f);
        }
    }

    /** Build a page-indicator name like "Аукцион (2/5)" */
    public static String pageTitle(String base, int page, int total) {
        return ColorUtil.translate(base + " &8(" + (page + 1) + "&8/&7" + total + "&8)");
    }
}
