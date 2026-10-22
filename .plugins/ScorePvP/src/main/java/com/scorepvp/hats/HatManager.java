package com.scorepvp.hats;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.PlayerSettings;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the hat catalogue from hats.yml and drives equip / remove / apply.
 *
 * <p>Hats are cosmetic: a COAL base item + custom_model_data resolved by the
 * ScoreCosmetics resource pack. The chosen hat id persists in
 * {@link PlayerSettings#getHat()} (player_settings.hat). Unlike the original
 * HopliteCosmetics, hats are NOT locked — the player may freely take the hat
 * off in the lobby; only the current selection is remembered.
 *
 * <p>Lobby: the hat is worn as the COAL item in the helmet slot (re-applied on
 * lobby entry). Duel: the hat's custom_model_data is stamped onto the kit's
 * real helmet (via {@link #applyToHelmet}), preserving armor + enchants.
 */
public class HatManager {

    /**
     * A shop tier: fixed price + button slot for all its hats. The button shows
     * {@code previewHat}'s model when set, else falls back to {@code icon}.
     */
    public record Category(String id, String displayName, int price,
                           String previewHat, org.bukkit.Material icon, int slot) {}

    private final ScorePvP plugin;
    private final NamespacedKey hatTagKey;

    private final Map<String, HatDefinition> hats = new LinkedHashMap<>();
    private final List<HatDefinition> ordered = new ArrayList<>();
    /** category id → category (file order: uncommon, rare, legendary). */
    private final Map<String, Category> categories = new LinkedHashMap<>();
    /** uuid → set of owned hat ids (loaded on join, mutated on purchase). */
    private final Map<java.util.UUID, java.util.Set<String>> ownedHats = new java.util.concurrent.ConcurrentHashMap<>();
    /** uuids whose owned-hats set has finished loading from the DB. */
    private final java.util.Set<java.util.UUID> ownedLoaded = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Resource-pack + GUI/message config, refreshed on reload().
    private boolean rpEnabled, rpMandatory;
    private String rpUrl, rpSha1, rpPrompt, rpDeclineKick, rpFailedKick, rpNotLoaded;
    private String guiTitle, removeHatName, prevName, nextName, pageIndicator, fillerMaterial;
    private List<String> removeHatLore;
    private HatDefinition.HatConfig guiLore;
    private String msgEquipped, msgRemoved, msgNoPerm, msgReload;

    // Shop config (refreshed on reload).
    private String shopTitle, shopFiller, shopExitName, shopPrevName, shopNextName,
            shopConfirmTitle, shopConfirmCancel, shopConfirmInfo, shopConfirmPrice, shopConfirmAccept,
            shopLoreEquipped, shopLoreOwned, shopLoreLocked, shopLorePrice,
            shopBought, shopNotEnough, shopEquipped;
    private int shopExitSlot, shopPrevSlot, shopNextSlot;
    private int shopPrevCmd, shopNextCmd;

    public HatManager(ScorePvP plugin) {
        this.plugin = plugin;
        this.hatTagKey = new NamespacedKey(plugin, "scorepvp_hat");
        reload();
    }

    public NamespacedKey getHatTagKey() { return hatTagKey; }

    public void reload() {
        hats.clear();
        ordered.clear();
        File f = new File(plugin.getDataFolder(), "hats.yml");
        if (!f.exists()) { plugin.getLogger().warning("[ScorePvP] hats.yml missing."); return; }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);

        rpEnabled     = cfg.getBoolean("resource-pack.enabled", true);
        rpMandatory   = cfg.getBoolean("resource-pack.mandatory", true);
        rpUrl         = cfg.getString("resource-pack.url", "");
        rpSha1        = cfg.getString("resource-pack.sha1", "");
        rpPrompt      = cfg.getString("resource-pack.prompt-message", "&6Resource pack required.");
        rpDeclineKick = cfg.getString("resource-pack.decline-kick-message", "&cResource pack required!");
        rpFailedKick  = cfg.getString("resource-pack.failed-kick-message", "&cResource pack failed to load!");
        rpNotLoaded   = cfg.getString("resource-pack.not-loaded-message", "&cLoad the resource pack first!");

        guiTitle      = cfg.getString("gui.title", "&8Hats");
        removeHatName = cfg.getString("gui.remove-hat-name", "&cRemove Hat");
        removeHatLore = cfg.getStringList("gui.remove-hat-lore");
        prevName      = cfg.getString("gui.prev-page-name", "&e« Previous");
        nextName      = cfg.getString("gui.next-page-name", "&eNext »");
        pageIndicator = cfg.getString("gui.page-indicator-name", "&6Page {page}/{total}");
        fillerMaterial= cfg.getString("gui.filler-material", "GRAY_STAINED_GLASS_PANE");
        guiLore = new HatDefinition.HatConfig(
                cfg.getString("gui.lore-equipped", "&a✔ Equipped"),
                cfg.getString("gui.lore-unlocked", "&7Click to equip"),
                cfg.getString("gui.lore-locked", "&cLocked"));

        msgEquipped = cfg.getString("messages.hat-equipped", "&aEquipped {hat}!");
        msgRemoved  = cfg.getString("messages.hat-removed", "&7Hat removed.");
        msgNoPerm   = cfg.getString("messages.no-permission", "&cThis hat is locked.");
        msgReload   = cfg.getString("messages.reload-success", "&aHats reloaded.");

        // ── Shop config ──────────────────────────────────────────────────────────
        shopTitle        = cfg.getString("shop.title", "&8Hats Shop");
        shopFiller       = cfg.getString("shop.filler-material", "GRAY_STAINED_GLASS_PANE");
        shopExitSlot     = cfg.getInt("shop.exit-slot", 49);
        shopExitName     = cfg.getString("shop.exit-name", "&cExit");
        shopPrevSlot     = cfg.getInt("shop.prev-slot", 48);
        shopPrevName     = cfg.getString("shop.prev-name", "&e« Previous");
        shopNextSlot     = cfg.getInt("shop.next-slot", 50);
        shopNextName     = cfg.getString("shop.next-name", "&eNext »");
        shopPrevCmd      = cfg.getInt("shop.prev-custom-model-data", 0);
        shopNextCmd      = cfg.getInt("shop.next-custom-model-data", 0);
        shopLoreEquipped = cfg.getString("shop.lore-equipped", "&a✔ Equipped");
        shopLoreOwned    = cfg.getString("shop.lore-owned", "&7Click to equip");
        shopLoreLocked   = cfg.getString("shop.lore-locked", "&cNot purchased");
        shopLorePrice    = cfg.getString("shop.lore-price", "&7Price: &f{price}");
        shopConfirmTitle = cfg.getString("shop.confirm-title", "&8Confirm purchase");
        shopConfirmCancel= cfg.getString("shop.confirm-cancel", "&cCancel");
        shopConfirmInfo  = cfg.getString("shop.confirm-info", "&e{hat}");
        shopConfirmPrice = cfg.getString("shop.confirm-price", "&7Price: &f{price}");
        shopConfirmAccept= cfg.getString("shop.confirm-accept", "&aBuy");
        shopBought       = cfg.getString("shop.bought", "&aBought {hat}!");
        shopNotEnough    = cfg.getString("shop.not-enough", "&cNot enough silver! Need {price}.");
        shopEquipped     = cfg.getString("shop.equipped", "&aEquipped {hat}!");

        // ── Categories ───────────────────────────────────────────────────────────
        categories.clear();
        ConfigurationSection cs = cfg.getConfigurationSection("categories");
        if (cs != null) {
            for (String cid : cs.getKeys(false)) {
                ConfigurationSection e = cs.getConfigurationSection(cid);
                if (e == null) continue;
                Material icon = Material.matchMaterial(e.getString("icon", "PAPER"));
                if (icon == null) icon = Material.PAPER;
                categories.put(cid, new Category(cid,
                        e.getString("display-name", "&f" + cid),
                        e.getInt("price", 0),
                        emptyToNull(e.getString("preview-hat", "")),
                        icon, e.getInt("slot", -1)));
            }
        }

        ConfigurationSection hs = cfg.getConfigurationSection("hats");
        if (hs == null) { plugin.getLogger().warning("[ScorePvP] hats.yml has no 'hats' section."); return; }
        String defaultCat = categories.keySet().stream().findFirst().orElse("uncommon");
        for (String id : hs.getKeys(false)) {
            ConfigurationSection e = hs.getConfigurationSection(id);
            if (e == null) continue;
            int cmd = e.getInt("custom-model-data", -1);
            if (cmd < 0) continue;
            Material mat = Material.matchMaterial(e.getString("icon-material", "COAL"));
            if (mat == null) mat = Material.COAL;
            String cat = e.getString("category", defaultCat);
            if (!categories.containsKey(cat)) cat = defaultCat;
            HatDefinition hat = new HatDefinition(id,
                    e.getString("display-name", "&f" + id), cmd, mat, e.getStringList("lore"), cat);
            hats.put(id, hat);
            ordered.add(hat);
        }
        plugin.getLogger().info("[ScorePvP] Loaded " + hats.size() + " hats in " + categories.size() + " categories.");
    }

    // ── Catalogue ────────────────────────────────────────────────────────────

    public HatDefinition getHat(String id)        { return id == null ? null : hats.get(id); }
    public List<HatDefinition> getOrdered()       { return ordered; }
    public HatDefinition.HatConfig guiLore()      { return guiLore; }
    public int getTotalPages(int pageSize) {
        return ordered.isEmpty() ? 1 : (int) Math.ceil(ordered.size() / (double) pageSize);
    }

    /**
     * True if the player may EQUIP this hat — i.e. they own it. A hat is owned
     * when it was purchased (player_hats), or unlocked for free by permission
     * ({@code scorepvp.hats.all} or {@code scorepvp.hat.<id>}).
     */
    public boolean canUse(Player p, HatDefinition hat) {
        return owns(p, hat);
    }

    // ── Categories ────────────────────────────────────────────────────────────

    public java.util.Collection<Category> getCategories() { return categories.values(); }
    public Category getCategory(String id) { return id == null ? null : categories.get(id); }
    public String firstCategoryId() { return categories.keySet().stream().findFirst().orElse(null); }

    /** Hats belonging to a category, in file order. */
    public List<HatDefinition> hatsInCategory(String categoryId) {
        List<HatDefinition> out = new ArrayList<>();
        for (HatDefinition h : ordered) if (h.getCategory().equals(categoryId)) out.add(h);
        return out;
    }

    /** The silver price of a hat, taken from its category (0 if unknown). */
    public int priceOf(HatDefinition hat) {
        if (hat == null) return 0;
        Category c = categories.get(hat.getCategory());
        return c != null ? c.price() : 0;
    }

    // ── Ownership ─────────────────────────────────────────────────────────────

    public void loadOwnedAsync(java.util.UUID uuid) {
        java.util.concurrent.CompletableFuture
                .supplyAsync(() -> plugin.getStorage().getMain().loadHats(uuid))
                .thenAccept(owned -> {
                    java.util.Set<String> set = java.util.concurrent.ConcurrentHashMap.newKeySet();
                    set.addAll(owned);
                    ownedHats.put(uuid, set);
                    ownedLoaded.add(uuid);
                    // Now that ownership is known, apply the saved lobby hat.
                    org.bukkit.entity.Player p = plugin.getServer().getPlayer(uuid);
                    if (p != null && p.isOnline())
                        plugin.getServer().getScheduler().runTask(plugin, () -> applyLobbyHat(p));
                })
                .exceptionally(ex -> {
                    // Leave ownedLoaded false so the cosmetics/hats open path
                    // re-triggers the load instead of showing an empty (unowned)
                    // shop that would let the player re-buy hats they own.
                    plugin.getLogger().warning("[ScorePvP] Owned-hats load failed for "
                            + uuid + ": " + ex.getMessage());
                    return null;
                });
    }

    /** True once the player's owned-hats set has finished loading from the DB. */
    public boolean isOwnedLoaded(java.util.UUID uuid) { return ownedLoaded.contains(uuid); }

    public void unloadOwned(java.util.UUID uuid) {
        ownedHats.remove(uuid);
        ownedLoaded.remove(uuid);
    }

    /** A hat is owned if permission-unlocked or already purchased. */
    public boolean owns(Player p, HatDefinition hat) {
        if (hat == null) return false;
        if (p.hasPermission(HatDefinition.PERMISSION_ALL)) return true;
        if (p.hasPermission(hat.getPermission())) return true;
        java.util.Set<String> set = ownedHats.get(p.getUniqueId());
        return set != null && set.contains(hat.getId());
    }

    /** Records a hat as purchased (cache + DB). */
    public void grant(java.util.UUID uuid, String hatId) {
        ownedHats.computeIfAbsent(uuid, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(hatId);
        java.util.concurrent.CompletableFuture.runAsync(
                () -> plugin.getStorage().getMain().addHat(uuid, hatId))
                .exceptionally(ex -> {
                    // A silent failure here is what made purchased hats "reset"
                    // after a restart — the silver was spent but the row never
                    // persisted. Surface it so the DB issue can be fixed.
                    plugin.getLogger().severe("[ScorePvP] Failed to persist purchased hat "
                            + hatId + " for " + uuid + ": " + ex.getMessage());
                    return null;
                });
    }

    // ── Shop config getters ─────────────────────────────────────────────────────

    public String shopTitle()        { return shopTitle; }
    public String shopFiller()       { return shopFiller; }
    public int shopExitSlot()        { return shopExitSlot; }
    public String shopExitName()     { return shopExitName; }
    public int shopPrevSlot()        { return shopPrevSlot; }
    public String shopPrevName()     { return shopPrevName; }
    public int shopNextSlot()        { return shopNextSlot; }
    public String shopNextName()     { return shopNextName; }
    public int shopPrevCmd()         { return shopPrevCmd; }
    public int shopNextCmd()         { return shopNextCmd; }
    public String shopLoreEquipped() { return shopLoreEquipped; }
    public String shopLoreOwned()    { return shopLoreOwned; }
    public String shopLoreLocked()   { return shopLoreLocked; }
    public String shopLorePrice()    { return shopLorePrice; }
    public String shopConfirmTitle() { return shopConfirmTitle; }
    public String shopConfirmCancel(){ return shopConfirmCancel; }
    public String shopConfirmInfo()  { return shopConfirmInfo; }
    public String shopConfirmPrice() { return shopConfirmPrice; }
    public String shopConfirmAccept(){ return shopConfirmAccept; }
    public String shopBought()       { return shopBought; }
    public String shopNotEnough()    { return shopNotEnough; }
    public String shopEquipped()     { return shopEquipped; }

    // ── RP / GUI / message getters ─────────────────────────────────────────────

    public boolean rpEnabled()        { return rpEnabled; }
    public boolean rpMandatory()      { return rpMandatory; }
    public String rpUrl()             { return rpUrl; }
    public String rpSha1()            { return rpSha1; }
    public String rpPrompt()          { return rpPrompt; }
    public String rpDeclineKick()     { return rpDeclineKick; }
    public String rpFailedKick()      { return rpFailedKick; }
    public String rpNotLoaded()       { return rpNotLoaded; }
    public String guiTitle()          { return guiTitle; }
    public String removeHatName()     { return removeHatName; }
    public List<String> removeHatLore(){ return removeHatLore; }
    public String prevName()          { return prevName; }
    public String nextName()          { return nextName; }
    public String pageIndicator()     { return pageIndicator; }
    public String fillerMaterial()    { return fillerMaterial; }
    public String msgEquipped()       { return msgEquipped; }
    public String msgRemoved()        { return msgRemoved; }
    public String msgNoPerm()         { return msgNoPerm; }
    public String msgReload()         { return msgReload; }

    // ── Item tagging ────────────────────────────────────────────────────────────

    /** The hat id stored on an item, or null if it isn't a ScorePvP hat item. */
    public String hatIdOf(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(hatTagKey, PersistentDataType.STRING);
    }

    /** Builds the wearable, tagged hat item for the lobby helmet slot. */
    public ItemStack buildTaggedHat(HatDefinition hat) {
        ItemStack it = hat.buildHatItem();
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(hatTagKey, PersistentDataType.STRING, hat.getId());
            it.setItemMeta(meta);
        }
        return it;
    }

    // ── Selection ────────────────────────────────────────────────────────────

    public String getSelected(Player p) {
        PlayerSettings st = plugin.getSettingsManager().get(p.getUniqueId());
        return st != null ? st.getHat() : null;
    }

    /** Persists the chosen hat id (null = none) and re-applies in the lobby. */
    public void setSelected(Player p, String hatId) {
        PlayerSettings st = plugin.getSettingsManager().get(p.getUniqueId());
        if (st == null) return;
        st.setHat(hatId);
        plugin.getSettingsManager().persist(p.getUniqueId());
    }

    // ── Lobby wear ─────────────────────────────────────────────────────────────

    /** True only in the lobby (no match / no queue / no party) and RP-safe. */
    public boolean inLobby(Player p) {
        if (plugin.getMatchManager() != null && plugin.getMatchManager().isInMatch(p.getUniqueId())) return false;
        if (plugin.getDuelManager() != null && plugin.getDuelManager().isInQueue(p.getUniqueId())) return false;
        if (plugin.getPartyManager() != null && plugin.getPartyManager().isInParty(p.getUniqueId())) return false;
        return true;
    }

    /**
     * Puts the chosen hat on in the lobby helmet slot, but only if that slot is
     * empty or already a cosmetic hat (never clobbers a real helmet). No-op
     * outside the lobby.
     */
    public void applyLobbyHat(Player p) {
        if (!inLobby(p)) return;
        String id = getSelected(p);
        HatDefinition hat = getHat(id);
        if (hat == null) return;
        if (!owns(p, hat)) return; // lost permission / never owned → don't wear
        ItemStack helmet = p.getInventory().getHelmet();
        if (!(helmet == null || helmet.getType() == Material.AIR || hatIdOf(helmet) != null)) return;
        p.getInventory().setHelmet(buildTaggedHat(hat));
        p.updateInventory();
    }

    /** Removes a cosmetic hat from the lobby helmet slot (leaves real helmets). */
    public void removeLobbyHat(Player p) {
        ItemStack helmet = p.getInventory().getHelmet();
        if (hatIdOf(helmet) != null) {
            p.getInventory().setHelmet(null);
            p.updateInventory();
        }
    }

    /**
     * Deliberately takes the cosmetic hat off (the /hats RMB-unset path). Same
     * as {@link #removeLobbyHat} today, but kept distinct so the lobby-lock
     * listener can tell an intentional unset apart from a player trying to drag
     * the hat out of their head.
     */
    public void forceRemoveLobbyHat(Player p) {
        removeLobbyHat(p);
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
