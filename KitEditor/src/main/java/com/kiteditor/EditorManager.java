package com.kiteditor;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class EditorManager {

    private final JavaPlugin plugin;
    private final KitManager kitManager;

    private final Map<UUID, Integer> kitListPage = new HashMap<>();
    private final Map<UUID, String> selectedKit = new HashMap<>();
    private final Map<UUID, ItemStack[]> editingKit = new HashMap<>();

    public EditorManager(JavaPlugin plugin, KitManager kitManager) {
        this.plugin = plugin;
        this.kitManager = kitManager;
    }

    public void openKitList(Player player, int page) {
        List<String> kits = kitManager.getKitNames();
        int totalPages = (int) Math.ceil(kits.size() / 45.0);
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages - 1;

        kitListPage.put(player.getUniqueId(), page);

        Inventory inv = Bukkit.createInventory(null, 54, "§8Kit Editor §7- Страница " + (page + 1) + "/" + Math.max(totalPages, 1));

        int start = page * 45;
        for (int i = 0; i < 45 && (start + i) < kits.size(); i++) {
            String kitName = kits.get(start + i);
            ItemStack item = new ItemStack(Material.CHEST);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName("§a" + kitName);
            meta.setLore(Arrays.asList("§7Нажмите чтобы редактировать"));
            item.setItemMeta(meta);
            inv.setItem(i, item);
        }

        if (page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prev.getItemMeta();
            prevMeta.setDisplayName("§cНазад");
            prev.setItemMeta(prevMeta);
            inv.setItem(48, prev);
        }

        if (page < totalPages - 1) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = next.getItemMeta();
            nextMeta.setDisplayName("§aВперед");
            next.setItemMeta(nextMeta);
            inv.setItem(50, next);
        }

        ItemStack glass = createGlass();
        for (int i = 45; i < 54; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, glass);
        }

        player.openInventory(inv);
    }

    public void openKitEditor(Player player, String kitName) {
        selectedKit.put(player.getUniqueId(), kitName);

        ItemStack[] items = kitManager.loadPlayerKit(player.getUniqueId(), kitName);
        editingKit.put(player.getUniqueId(), items.clone());

        Inventory inv = Bukkit.createInventory(null, 54, "§8Редактор: " + kitName);

        for (int i = 0; i < 36; i++) {
            inv.setItem(i, items[i]);
        }

        ItemStack glass = createGlass();
        for (int i = 36; i < 45; i++) {
            inv.setItem(i, glass);
        }

        inv.setItem(45, items[36] != null ? items[36] : new ItemStack(Material.AIR));
        inv.setItem(46, items[37] != null ? items[37] : new ItemStack(Material.AIR));
        inv.setItem(47, items[38] != null ? items[38] : new ItemStack(Material.AIR));
        inv.setItem(48, items[39] != null ? items[39] : new ItemStack(Material.AIR));

        inv.setItem(49, items[40] != null ? items[40] : new ItemStack(Material.AIR));

        inv.setItem(50, glass);

        ItemStack back = new ItemStack(Material.RED_DYE);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName("§cНазад");
        backMeta.setLore(Arrays.asList("§7Вернуться к списку китов"));
        back.setItemMeta(backMeta);
        inv.setItem(51, back);

        ItemStack reset = new ItemStack(Material.GRAY_DYE);
        ItemMeta resetMeta = reset.getItemMeta();
        resetMeta.setDisplayName("§7Сбросить");
        resetMeta.setLore(Arrays.asList("§7Сбросить кит к стандартному"));
        reset.setItemMeta(resetMeta);
        inv.setItem(52, reset);

        ItemStack save = new ItemStack(Material.GREEN_DYE);
        ItemMeta saveMeta = save.getItemMeta();
        saveMeta.setDisplayName("§aСохранить");
        saveMeta.setLore(Arrays.asList("§7Сохранить изменения"));
        save.setItemMeta(saveMeta);
        inv.setItem(53, save);

        player.openInventory(inv);
    }

    public void saveKit(Player player, Inventory inv) {
        UUID uuid = player.getUniqueId();
        String kitName = selectedKit.get(uuid);
        if (kitName == null) return;

        ItemStack[] items = new ItemStack[54];

        for (int i = 0; i < 36; i++) {
            items[i] = inv.getItem(i);
        }

        items[36] = inv.getItem(45);
        items[37] = inv.getItem(46);
        items[38] = inv.getItem(47);
        items[39] = inv.getItem(48);
        items[40] = inv.getItem(49);

        kitManager.savePlayerKit(uuid, kitName, items);
        player.sendMessage("§aКит §f" + kitName + " §aсохранен!");
    }

    public void resetKit(Player player) {
        UUID uuid = player.getUniqueId();
        String kitName = selectedKit.get(uuid);
        if (kitName == null) return;

        kitManager.resetPlayerKit(uuid, kitName);
        openKitEditor(player, kitName);
        player.sendMessage("§7Кит §f" + kitName + " §7сброшен к стандартному!");
    }

    public int getKitListPage(UUID uuid) {
        return kitListPage.getOrDefault(uuid, 0);
    }

    public String getSelectedKit(UUID uuid) {
        return selectedKit.get(uuid);
    }

    public boolean isEditingKit(UUID uuid) {
        return selectedKit.containsKey(uuid);
    }

    public boolean isKitSlot(int slot) {
        return (slot >= 0 && slot <= 35) || (slot >= 45 && slot <= 49);
    }

    public boolean isButtonSlot(int slot) {
        return slot == 51 || slot == 52 || slot == 53;
    }

    private ItemStack createGlass() {
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = glass.getItemMeta();
        meta.setDisplayName(" ");
        glass.setItemMeta(meta);
        return glass;
    }

    public void cleanup(UUID uuid) {
        kitListPage.remove(uuid);
        selectedKit.remove(uuid);
        editingKit.remove(uuid);
    }
}
