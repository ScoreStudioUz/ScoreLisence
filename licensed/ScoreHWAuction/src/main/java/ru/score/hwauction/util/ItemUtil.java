package ru.score.hwauction.util;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public final class ItemUtil {

    private ItemUtil() {}

    /**
     * Proper check: considers stackable items and partial stacks.
     */
    public static boolean hasSpace(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) return true;
        PlayerInventory inv = player.getInventory();
        int needed = item.getAmount();

        for (int i = 0; i < 36; i++) {
            ItemStack slot = inv.getItem(i);
            if (slot == null || slot.getType().isAir()) {
                needed -= item.getMaxStackSize();
            } else if (slot.isSimilar(item)) {
                needed -= (slot.getMaxStackSize() - slot.getAmount());
            }
            if (needed <= 0) return true;
        }
        return false;
    }

    /**
     * Get display name of item (stripping colors), or formatted material name.
     */
    public static String getDisplayName(ItemStack item) {
        if (item == null) return "Unknown";
        if (item.hasItemMeta() && item.getItemMeta() != null
                && item.getItemMeta().hasDisplayName()) {
            return ColorUtil.strip(item.getItemMeta().getDisplayName());
        }
        String raw = item.getType().name().replace("_", " ");
        StringBuilder sb = new StringBuilder();
        for (String word : raw.split(" ")) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)))
                  .append(word.substring(1).toLowerCase()).append(" ");
            }
        }
        return sb.toString().trim();
    }

    /**
     * Clone item with specific amount, capped at maxStackSize.
     */
    public static ItemStack cloneWithAmount(ItemStack item, int amount) {
        ItemStack clone = item.clone();
        clone.setAmount(Math.min(amount, item.getMaxStackSize()));
        return clone;
    }

    /**
     * Remove exactly `amount` items of the given type from player's hand.
     */
    public static void removeFromHand(Player player, int amount) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getAmount() <= amount) {
            player.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(hand.getAmount() - amount);
        }
    }
}
