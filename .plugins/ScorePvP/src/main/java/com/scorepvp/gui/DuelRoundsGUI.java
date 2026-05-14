package com.scorepvp.gui;

import com.scorepvp.ScorePvP;
import com.scorepvp.util.ColorUtil;
import com.scorepvp.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Duel flow — Step 2: Choose number of rounds.
 * Reads layout from item_menus/duel_rounds.yml.
 */
public class DuelRoundsGUI {

    private String title;
    private int guiSize;
    private Material fillerMaterial;
    private String fillerName;
    private int[] roundSlots;
    private int[] roundValues;
    private int defaultRounds;
    private Material selectedMaterial;
    private Material unselectedMaterial;
    private int slotCancel;
    private int slotHead;
    private int slotSend;
    private Material cancelMaterial;
    private Material sendMaterial;
    private String headName;

    private final ScorePvP plugin;

    /** challenger uuid → context */
    private final Map<UUID, DuelContext> contexts = new ConcurrentHashMap<>();

    /** challenger uuid → selected round count */
    private final Map<UUID, Integer> selectedRoundsMap = new ConcurrentHashMap<>();

    public DuelRoundsGUI(ScorePvP plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        File file = new File(plugin.getDataFolder(), "item_menus/duel_rounds.yml");
        if (!file.exists()) {
            applyDefaults();
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        title = ColorUtil.color(cfg.getString("title", "&0Дуэль: раунды"));
        guiSize = cfg.getInt("size", 27);
        try { fillerMaterial = Material.valueOf(cfg.getString("filler.material", "BLUE_STAINED_GLASS_PANE")); }
        catch (IllegalArgumentException e) { fillerMaterial = Material.BLUE_STAINED_GLASS_PANE; }
        fillerName = cfg.getString("filler.name", "&r");

        List<Integer> rSlots = cfg.getIntegerList("round-slots");
        if (rSlots.isEmpty()) rSlots = List.of(10,11,12,13,14,15,16);
        roundSlots = rSlots.stream().mapToInt(Integer::intValue).toArray();

        List<Integer> rVals = cfg.getIntegerList("round-values");
        if (rVals.isEmpty()) rVals = List.of(1,2,3,5,7,10,15);
        roundValues = rVals.stream().mapToInt(Integer::intValue).toArray();

        defaultRounds = cfg.getInt("default-rounds", 3);
        try { selectedMaterial = Material.valueOf(cfg.getString("selected-material", "MAP")); }
        catch (IllegalArgumentException e) { selectedMaterial = Material.MAP; }
        try { unselectedMaterial = Material.valueOf(cfg.getString("unselected-material", "PAPER")); }
        catch (IllegalArgumentException e) { unselectedMaterial = Material.PAPER; }

        slotCancel = cfg.getInt("cancel-slot", 21);
        slotHead   = cfg.getInt("head-slot", 22);
        slotSend   = cfg.getInt("send-slot", 23);
        try { cancelMaterial = Material.valueOf(cfg.getString("cancel.material", "BARRIER")); }
        catch (IllegalArgumentException e) { cancelMaterial = Material.BARRIER; }
        try { sendMaterial = Material.valueOf(cfg.getString("send.material", "GREEN_CONCRETE")); }
        catch (IllegalArgumentException e) { sendMaterial = Material.GREEN_CONCRETE; }
        headName = cfg.getString("head-slot-display.name", "&b{player}");
    }

    private void applyDefaults() {
        title = ColorUtil.color("&0Дуэль: раунды");
        guiSize = 27;
        fillerMaterial = Material.BLUE_STAINED_GLASS_PANE;
        fillerName = "&r";
        roundSlots = new int[]{10,11,12,13,14,15,16};
        roundValues = new int[]{1,2,3,5,7,10,15};
        defaultRounds = 3;
        selectedMaterial = Material.MAP;
        unselectedMaterial = Material.PAPER;
        slotCancel = 21; slotHead = 22; slotSend = 23;
        cancelMaterial = Material.BARRIER;
        sendMaterial = Material.GREEN_CONCRETE;
        headName = "&b{player}";
    }

    // ── Records ───────────────────────────────────────────────────────────────

    public record DuelContext(Player target, String modeName) {}
    public record RoundSelection(Player target, String modeName, int rounds) {}

    // ── Open ──────────────────────────────────────────────────────────────────

    public void open(Player challenger, Player target, String modeName) {
        contexts.put(challenger.getUniqueId(), new DuelContext(target, modeName));
        selectedRoundsMap.put(challenger.getUniqueId(), defaultRounds);

        Inventory inv = Bukkit.createInventory(null, guiSize, title);

        ItemStack glass = new ItemBuilder(fillerMaterial).name(fillerName).build();
        for (int i = 0; i < guiSize; i++) inv.setItem(i, glass);

        // Round options
        for (int i = 0; i < roundSlots.length && i < roundValues.length; i++) {
            int r = roundValues[i];
            boolean selected = (r == defaultRounds);
            inv.setItem(roundSlots[i], buildRoundItem(r, selected));
        }

        // Cancel button
        inv.setItem(slotCancel, new ItemBuilder(cancelMaterial)
                .name(plugin.getConfigManager().rawStr("duel-gui.cancel-name"))
                .lore(plugin.getConfigManager().rawStr("duel-gui.cancel-lore"))
                .build());

        // Player head
        inv.setItem(slotHead, buildPlayerHead(target));

        // Send button
        inv.setItem(slotSend, new ItemBuilder(sendMaterial)
                .name(plugin.getConfigManager().rawStr("duel-gui.send-name"))
                .lore(plugin.getConfigManager().rawStr("duel-gui.send-lore"))
                .build());

        challenger.openInventory(inv);
    }

    // ── Click ─────────────────────────────────────────────────────────────────

    public RoundSelection handleClick(Player challenger, int slot) {
        DuelContext ctx = contexts.get(challenger.getUniqueId());
        if (ctx == null) return null;

        // Cancel button
        if (slot == slotCancel) {
            contexts.remove(challenger.getUniqueId());
            selectedRoundsMap.remove(challenger.getUniqueId());
            challenger.closeInventory();
            return null;
        }

        // Round selection
        for (int i = 0; i < roundSlots.length && i < roundValues.length; i++) {
            if (roundSlots[i] == slot) {
                int rounds = roundValues[i];
                selectedRoundsMap.put(challenger.getUniqueId(), rounds);
                updateRoundVisuals(challenger, rounds);
                return null;
            }
        }

        // Send button
        if (slot == slotSend) {
            int rounds = selectedRoundsMap.getOrDefault(challenger.getUniqueId(), defaultRounds);
            contexts.remove(challenger.getUniqueId());
            selectedRoundsMap.remove(challenger.getUniqueId());
            return new RoundSelection(ctx.target(), ctx.modeName(), rounds);
        }

        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public boolean isThisGUI(String guiTitle) {
        return guiTitle.equals(title);
    }

    public void clearContext(UUID uuid) {
        contexts.remove(uuid);
        selectedRoundsMap.remove(uuid);
    }

    private ItemStack buildRoundItem(int rounds, boolean selected) {
        Material mat = selected ? selectedMaterial : unselectedMaterial;
        String color = selected ? "&a" : "&e";
        String prefix = selected ? "&a» " : "";
        return new ItemBuilder(mat)
                .name(prefix + color + rounds + " " + roundWord(rounds))
                .lore(selected
                        ? plugin.getConfigManager().rawStr("duel-gui.round-selected")
                        : plugin.getConfigManager().rawStr("duel-gui.round-click"))
                .amount(Math.max(1, rounds))
                .build();
    }

    private void updateRoundVisuals(Player challenger, int selectedValue) {
        Inventory inv = challenger.getOpenInventory().getTopInventory();
        if (inv == null) return;
        for (int i = 0; i < roundSlots.length && i < roundValues.length; i++) {
            inv.setItem(roundSlots[i], buildRoundItem(roundValues[i], roundValues[i] == selectedValue));
        }
    }

    private ItemStack buildPlayerHead(Player target) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        meta.setOwningPlayer(target);
        meta.setDisplayName(ColorUtil.color(headName.replace("{player}", target.getName())));
        meta.setLore(java.util.List.of(ColorUtil.color(plugin.getConfigManager().rawStr("profile.opponent-label"))));
        skull.setItemMeta(meta);
        return skull;
    }

    private String roundWord(int r) {
        if (r == 1) return plugin.getConfigManager().rawStr("duel-gui.round-word-1");
        if (r < 5)  return plugin.getConfigManager().rawStr("duel-gui.round-word-2-4");
        return plugin.getConfigManager().rawStr("duel-gui.round-word-5");
    }
}
