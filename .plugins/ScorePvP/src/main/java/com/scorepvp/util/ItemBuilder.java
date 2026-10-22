package com.scorepvp.util;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.List;

public class ItemBuilder {

    private final ItemStack item;
    private final ItemMeta meta;

    public ItemBuilder(Material material) {
        this(material, 1);
    }

    public ItemBuilder(Material material, int amount) {
        this.item = new ItemStack(material, amount);
        this.meta = item.getItemMeta();
    }

    public ItemBuilder(ItemStack base) {
        this.item = base.clone();
        this.meta = item.getItemMeta();
    }

    public ItemBuilder name(String name) {
        meta.setDisplayName(ColorUtil.color(name));
        return this;
    }

    public ItemBuilder lore(String... lines) {
        meta.setLore(Arrays.stream(lines).map(ColorUtil::color).toList());
        return this;
    }

    public ItemBuilder lore(List<String> lines) {
        meta.setLore(ColorUtil.color(lines));
        return this;
    }

    public ItemBuilder enchant(Enchantment enchantment, int level) {
        meta.addEnchant(enchantment, level, true);
        return this;
    }

    public ItemBuilder glow() {
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        return this;
    }

    public ItemBuilder flag(ItemFlag... flags) {
        meta.addItemFlags(flags);
        return this;
    }

    public ItemBuilder unbreakable() {
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        return this;
    }

    public ItemBuilder amount(int amount) {
        item.setAmount(amount);
        return this;
    }

    /** Section 4: custom model data support */
    public ItemBuilder customModelData(int data) {
        meta.setCustomModelData(data);
        return this;
    }

    /**
     * When true, {@link #build()} hides every vanilla tooltip line (attribute
     * modifiers like "+X Attack Damage", armor values, enchant text, dye/banner
     * lines, unbreakable, etc.) so menu icons read only their custom name + lore.
     * Defaults to true — practically all ItemBuilder uses are GUI icons. Real
     * given-to-player items that WANT their stats shown can opt out via
     * {@link #showTooltip()}. Hiding is purely cosmetic; item behaviour is
     * unchanged (attributes still apply, glint still shows).
     */
    private boolean hideTooltip = true;

    /** Opt out of the default tooltip-hiding (e.g. show enchants on a kit item). */
    public ItemBuilder showTooltip() {
        this.hideTooltip = false;
        return this;
    }

    public ItemStack build() {
        if (hideTooltip && meta != null) {
            meta.addItemFlags(ItemFlag.values());
        }
        item.setItemMeta(meta);
        return item;
    }
}
