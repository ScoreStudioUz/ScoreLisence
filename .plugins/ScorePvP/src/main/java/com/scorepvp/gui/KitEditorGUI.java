package com.scorepvp.gui;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.PvPMode;
import com.scorepvp.util.ColorUtil;
import com.scorepvp.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-mode personal kit editor (54-slot), opened from {@link KitMenuGUI}.
 *
 * <p>Standard Minecraft layout (hotbar at the BOTTOM), so it reads exactly like
 * the player's real inventory:
 * <pre>
 *   GUI  0      ← BACK button (RED_DYE, cancel without saving)
 *   GUI  1      ← decorative pane
 *   GUI  2..5   ← armor: helmet / chestplate / leggings / boots
 *   GUI  6      ← offhand (shield)
 *   GUI  7      ← decorative pane
 *   GUI  8      ← SAVE button (LIME_DYE)
 *   GUI  9..35  ← main inventory      (kit storage 9..35)
 *   GUI 36..44  ← divider row "↑ Inventory / ↓ Hotbar"
 *   GUI 45..53  ← hotbar              (kit storage 0..8)
 * </pre>
 *
 * Items can only be rearranged between editable slots — no creative needed and
 * nothing leaks into survival (enforced by {@link com.scorepvp.listener.GUIClickListener}).
 * Decoration / slots are configurable via item_menus/kit_editor.yml.
 */
public class KitEditorGUI {

    private String titleTemplate;
    private Material fillerMaterial;
    private String fillerName;

    private Material cornerMaterial;
    private String cornerName;
    private int[] cornerSlots;

    private Material dividerMaterial;
    private String dividerName;
    private List<String> dividerLore;
    private int[] dividerSlots;

    private int helmetSlot, chestSlot, legsSlot, bootsSlot, offhandSlot;
    private Material offhandPlaceholderMaterial;
    private String offhandPlaceholderName;
    private List<String> offhandPlaceholderLore;
    private int backSlot, resetSlot, saveSlot;
    private Material backMaterial, resetMaterial, saveMaterial;
    private String backName, resetName, saveName;
    private List<String> backLore, resetLore, saveLore;

    private final ScorePvP plugin;

    /** Player → the kit type currently being edited (so save knows the target). */
    private final Map<UUID, String> editing = new ConcurrentHashMap<>();

    public KitEditorGUI(ScorePvP plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        File file = new File(plugin.getDataFolder(), "item_menus/kit_editor.yml");
        if (!file.exists()) { applyDefaults(); return; }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        titleTemplate = cfg.getString("title", "&6Kit: {mode}");
        fillerMaterial = mat(cfg.getString("filler.material", "BLACK_STAINED_GLASS_PANE"), Material.BLACK_STAINED_GLASS_PANE);
        fillerName = cfg.getString("filler.name", "&r");

        cornerMaterial = mat(cfg.getString("corner.material", "RED_STAINED_GLASS_PANE"), Material.RED_STAINED_GLASS_PANE);
        cornerName = cfg.getString("corner.name", "&r");
        cornerSlots = intArray(cfg.getIntegerList("corner.slots"), new int[]{1, 7});

        dividerMaterial = mat(cfg.getString("divider.material", "RED_STAINED_GLASS_PANE"), Material.RED_STAINED_GLASS_PANE);
        dividerName = cfg.getString("divider.name", "&7↑ Inventar");
        dividerLore = cfg.getStringList("divider.lore");
        if (dividerLore.isEmpty()) dividerLore = List.of("&7↓ Hotbar");
        dividerSlots = intArray(cfg.getIntegerList("divider.slots"),
                new int[]{36, 37, 38, 39, 40, 41, 42, 43, 44});

        helmetSlot  = cfg.getInt("armor.helmet-slot", 2);
        chestSlot   = cfg.getInt("armor.chestplate-slot", 3);
        legsSlot    = cfg.getInt("armor.leggings-slot", 4);
        bootsSlot   = cfg.getInt("armor.boots-slot", 5);
        offhandSlot = cfg.getInt("offhand-slot", 6);
        offhandPlaceholderMaterial = mat(cfg.getString("offhand-placeholder.material", "ITEM_FRAME"), Material.ITEM_FRAME);
        offhandPlaceholderName = cfg.getString("offhand-placeholder.name", "&eOffhand");
        offhandPlaceholderLore = cfg.getStringList("offhand-placeholder.lore");
        if (offhandPlaceholderLore.isEmpty())
            offhandPlaceholderLore = List.of("&7Bu yerga offhand narsa", "&7joylashtira olasiz");

        backSlot  = cfg.getInt("buttons.back.slot", 0);
        resetSlot = cfg.getInt("buttons.reset.slot", -1);
        saveSlot  = cfg.getInt("buttons.save.slot", 8);
        backMaterial  = mat(cfg.getString("buttons.back.material",  "RED_DYE"),   Material.RED_DYE);
        resetMaterial = mat(cfg.getString("buttons.reset.material", "GRAY_DYE"),  Material.GRAY_DYE);
        saveMaterial  = mat(cfg.getString("buttons.save.material",  "LIME_DYE"),  Material.LIME_DYE);
        backName  = cfg.getString("buttons.back.name",  "&c&lBekor");
        resetName = cfg.getString("buttons.reset.name", "&7Tiklash (default)");
        saveName  = cfg.getString("buttons.save.name",  "&a&lSaqlash");
        backLore  = cfg.getStringList("buttons.back.lore");
        if (backLore.isEmpty()) backLore = List.of("&7Saqlamasdan chiqish");
        resetLore = cfg.getStringList("buttons.reset.lore");
        if (resetLore.isEmpty()) resetLore = List.of("&7Kitni standartga qaytarish");
        saveLore  = cfg.getStringList("buttons.save.lore");
        if (saveLore.isEmpty()) saveLore = List.of("&7O'zgarishlarni saqlash");
    }

    private void applyDefaults() {
        titleTemplate = "&6Kit: {mode}";
        fillerMaterial = Material.BLACK_STAINED_GLASS_PANE;
        fillerName = "&r";
        cornerMaterial = Material.RED_STAINED_GLASS_PANE;
        cornerName = "&r";
        cornerSlots = new int[]{1, 7};
        dividerMaterial = Material.RED_STAINED_GLASS_PANE;
        dividerName = "&7↑ Inventar";
        dividerLore = List.of("&7↓ Hotbar");
        dividerSlots = new int[]{36, 37, 38, 39, 40, 41, 42, 43, 44};
        helmetSlot = 2; chestSlot = 3; legsSlot = 4; bootsSlot = 5; offhandSlot = 6;
        offhandPlaceholderMaterial = Material.ITEM_FRAME;
        offhandPlaceholderName = "&eOffhand";
        offhandPlaceholderLore = List.of("&7Bu yerga offhand narsa", "&7joylashtira olasiz");
        backSlot = 0; resetSlot = -1; saveSlot = 8;
        backMaterial = Material.RED_DYE; resetMaterial = Material.GRAY_DYE; saveMaterial = Material.LIME_DYE;
        backName = "&c&lBekor"; resetName = "&7Tiklash (default)"; saveName = "&a&lSaqlash";
        backLore = List.of("&7Saqlamasdan chiqish");
        resetLore = List.of("&7Kitni standartga qaytarish");
        saveLore = List.of("&7O'zgarishlarni saqlash");
    }

    private Material mat(String name, Material def) {
        try { return Material.valueOf(name); } catch (IllegalArgumentException e) { return def; }
    }

    private int[] intArray(List<Integer> list, int[] def) {
        if (list == null || list.isEmpty()) return def;
        return list.stream().mapToInt(Integer::intValue).toArray();
    }

    // ── Storage ↔ GUI slot mapping (standard layout) ────────────────────────────
    //
    // Kit storage 0..8  = hotbar      → GUI 45..53 (bottom row)
    // Kit storage 9..35 = main inv    → GUI  9..35 (same positions)

    /** Maps a kit storage slot (0..35) to its GUI slot in the standard layout. */
    private static int storageToGui(int storage) {
        if (storage >= 0 && storage <= 8)  return 45 + storage;     // hotbar → bottom row
        if (storage >= 9 && storage <= 35) return storage;          // inv rows stay put
        return -1;
    }

    /** Inverse of {@link #storageToGui}; returns -1 if the GUI slot isn't a storage slot. */
    private static int guiToStorage(int gui) {
        if (gui >= 9 && gui <= 35)  return gui;       // main inventory
        if (gui >= 45 && gui <= 53) return gui - 45;  // hotbar
        return -1;
    }

    // ── Open ────────────────────────────────────────────────────────────────────

    public void open(Player player, String kitType) {
        PvPMode mode = resolveMode(kitType);
        String modeName = mode != null ? mode.getDisplayName() : kitType;
        String title = ColorUtil.color(titleTemplate.replace("{mode}", modeName));

        editing.put(player.getUniqueId(), kitType);

        Inventory inv = Bukkit.createInventory(null, 54, title);

        // Decoration: top control row (0..8) gets filler except the configured
        // slots; divider row gets its own pane. Storage slots stay empty.
        ItemStack glass  = new ItemBuilder(fillerMaterial).name(fillerName).build();
        for (int i = 0; i < 9; i++) inv.setItem(i, glass);

        ItemStack corner = new ItemBuilder(cornerMaterial).name(cornerName).build();
        for (int s : cornerSlots) if (s >= 0 && s < 54) inv.setItem(s, corner);

        ItemStack divider = new ItemBuilder(dividerMaterial).name(dividerName).lore(dividerLore).build();
        for (int s : dividerSlots) if (s >= 0 && s < 54) inv.setItem(s, divider);

        ItemStack[] kit = plugin.getKitManager().getModeKitOrDefault(player.getUniqueId(), kitType);
        // 0..35 storage → GUI positions
        for (int s = 0; s < 36; s++) {
            int g = storageToGui(s);
            if (g >= 0) inv.setItem(g, kit[s]);
        }
        // armor + offhand → their configured GUI slots
        inv.setItem(helmetSlot,  kit.length > 39 ? kit[39] : null); // 39 = helmet
        inv.setItem(chestSlot,   kit.length > 38 ? kit[38] : null); // 38 = chestplate
        inv.setItem(legsSlot,    kit.length > 37 ? kit[37] : null); // 37 = leggings
        inv.setItem(bootsSlot,   kit.length > 36 ? kit[36] : null); // 36 = boots
        ItemStack offhand = kit.length > 40 ? kit[40] : null;
        inv.setItem(offhandSlot, offhand != null && !offhand.getType().isAir()
                ? offhand : offhandPlaceholder());

        // Control buttons (overwrite decoration).
        if (backSlot  >= 0) inv.setItem(backSlot,  new ItemBuilder(backMaterial).name(backName).lore(backLore).build());
        if (resetSlot >= 0) inv.setItem(resetSlot, new ItemBuilder(resetMaterial).name(resetName).lore(resetLore).build());
        if (saveSlot  >= 0) inv.setItem(saveSlot,  new ItemBuilder(saveMaterial).name(saveName).lore(saveLore).build());

        player.openInventory(inv);
    }

    /** The decorative "drop an offhand item here" placeholder for an empty offhand slot. */
    public ItemStack offhandPlaceholder() {
        return new ItemBuilder(offhandPlaceholderMaterial)
                .name(offhandPlaceholderName)
                .lore(offhandPlaceholderLore)
                .build();
    }

    public int getOffhandSlot() { return offhandSlot; }
    public int getHelmetSlot()  { return helmetSlot; }
    public int getChestSlot()   { return chestSlot; }
    public int getLegsSlot()    { return legsSlot; }
    public int getBootsSlot()   { return bootsSlot; }

    /** True if the slot is one of the four armor slots. */
    public boolean isArmorSlot(int slot) {
        return slot == helmetSlot || slot == chestSlot || slot == legsSlot || slot == bootsSlot;
    }

    /**
     * Whether {@code item} is valid for the given armor GUI slot. Each slot only
     * accepts armor of its own body part (helmet slot → helmets, etc.). Carved
     * pumpkins / mob heads count as helmets (vanilla lets you wear them). Null /
     * air is always allowed (clearing the slot).
     */
    public boolean isValidForArmorSlot(int slot, ItemStack item) {
        if (item == null || item.getType().isAir()) return true;
        org.bukkit.inventory.EquipmentSlot es = equipSlotOf(item.getType());
        if (es == null) return false; // not wearable at all
        if (slot == helmetSlot) return es == org.bukkit.inventory.EquipmentSlot.HEAD;
        if (slot == chestSlot)  return es == org.bukkit.inventory.EquipmentSlot.CHEST;
        if (slot == legsSlot)   return es == org.bukkit.inventory.EquipmentSlot.LEGS;
        if (slot == bootsSlot)  return es == org.bukkit.inventory.EquipmentSlot.FEET;
        return true;
    }

    /** The body slot a material is worn on, or null if it isn't armor/wearable. */
    private static org.bukkit.inventory.EquipmentSlot equipSlotOf(Material m) {
        String n = m.name();
        if (n.endsWith("_HELMET") || m == Material.CARVED_PUMPKIN || n.endsWith("_HEAD") || n.endsWith("_SKULL"))
            return org.bukkit.inventory.EquipmentSlot.HEAD;
        if (n.endsWith("_CHESTPLATE") || m == Material.ELYTRA)
            return org.bukkit.inventory.EquipmentSlot.CHEST;
        if (n.endsWith("_LEGGINGS"))
            return org.bukkit.inventory.EquipmentSlot.LEGS;
        if (n.endsWith("_BOOTS"))
            return org.bukkit.inventory.EquipmentSlot.FEET;
        return null;
    }

    /** True if the stack is the offhand placeholder (so it's never saved as a kit item). */
    public boolean isOffhandPlaceholder(ItemStack item) {
        return item != null && item.getType() == offhandPlaceholderMaterial;
    }

    private PvPMode resolveMode(String kitType) {
        for (PvPMode m : plugin.getConfigManager().getModes()) {
            if (m.getKitType().equals(kitType)) return m;
        }
        return null;
    }

    // ── Title / routing helpers ──────────────────────────────────────────────────

    public boolean isThisGUI(String title) {
        // Title carries the dynamic mode name, so match on the fixed prefix
        // before the "{mode}" placeholder.
        String prefix = ColorUtil.color(titleTemplate.substring(
                0, Math.max(0, titleTemplate.indexOf("{mode}"))));
        return !prefix.isEmpty() && title.startsWith(prefix);
    }

    public String getEditing(UUID uuid) { return editing.get(uuid); }

    public boolean isEditableSlot(int slot) {
        if (guiToStorage(slot) >= 0) return true;
        return slot == helmetSlot || slot == chestSlot || slot == legsSlot
                || slot == bootsSlot || slot == offhandSlot;
    }

    public boolean isBackSlot(int slot)  { return backSlot  >= 0 && slot == backSlot; }
    public boolean isResetSlot(int slot) { return resetSlot >= 0 && slot == resetSlot; }
    public boolean isSaveSlot(int slot)  { return saveSlot  >= 0 && slot == saveSlot; }

    // ── Save / reset ─────────────────────────────────────────────────────────────

    /** Reads the editor inventory back into a 41-slot kit array and saves it. */
    public void save(Player player, Inventory inv) {
        String kitType = editing.get(player.getUniqueId());
        if (kitType == null) return;

        ItemStack[] kit = new ItemStack[41];
        for (int gui = 0; gui < 54; gui++) {
            int storage = guiToStorage(gui);
            if (storage >= 0) kit[storage] = clean(inv.getItem(gui));
        }
        kit[39] = clean(inv.getItem(helmetSlot));
        kit[38] = clean(inv.getItem(chestSlot));
        kit[37] = clean(inv.getItem(legsSlot));
        kit[36] = clean(inv.getItem(bootsSlot));
        kit[40] = clean(inv.getItem(offhandSlot));

        plugin.getKitManager().saveModeKit(player.getUniqueId(), kitType, kit);
        player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                + " " + plugin.getConfigManager().rawMsg("kit-editor.saved"));
    }

    public void reset(Player player) {
        String kitType = editing.get(player.getUniqueId());
        if (kitType == null) return;
        plugin.getKitManager().resetModeKit(player.getUniqueId(), kitType);
        player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                + " " + plugin.getConfigManager().rawMsg("kit-editor.reset"));
        open(player, kitType); // reopen with the default kit
    }

    public void cleanup(UUID uuid) {
        editing.remove(uuid);
    }

    /** Null-out filler/decoration/air so we never persist a glass pane into the kit. */
    private ItemStack clean(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        if (item.getType() == fillerMaterial) return null;
        if (item.getType() == cornerMaterial) return null;
        if (item.getType() == dividerMaterial) return null;
        if (item.getType() == offhandPlaceholderMaterial) return null;
        return item.clone();
    }
}
