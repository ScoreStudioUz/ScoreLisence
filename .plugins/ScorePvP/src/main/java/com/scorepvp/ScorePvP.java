package com.scorepvp;

import com.scorepvp.command.*;
import com.scorepvp.compat.BukkitNameTagProvider;
import com.scorepvp.compat.LuckPermsHook;
import com.scorepvp.compat.NameTagProvider;
import com.scorepvp.compat.TabNameTagProvider;
import com.scorepvp.storage.StorageService;
import com.scorepvp.gui.*;
import com.scorepvp.item.CustomItemRegistry;
import com.scorepvp.listener.*;
import com.scorepvp.manager.*;
import com.scorepvp.placeholder.ScorePvPExpansion;
import com.scorepvp.task.HotbarIntegrityTask;
import com.scorepvp.task.PlaytimeTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class ScorePvP extends JavaPlugin {

    // ── Managers ──────────────────────────────────────────────────────────────
    private StorageService       storageService;
    private ConfigManager        configManager;
    private HotbarManager        hotbarManager;
    private PartyManager         partyManager;
    private TierManager          tierManager;
    private SettingsManager      settingsManager;
    private KitManager           kitManager;
    private PlayerDataManager    playerDataManager;
    private ArenaManager         arenaManager;
    private ArenaRestoreManager  arenaRestoreManager;
    private ArenaBlockTracker    arenaBlockTracker;
    private MatchManager         matchManager;
    private DuelManager          duelManager;
    private StatsManager         statsManager;
    private MsgManager           msgManager;
    private FriendsManager       friendsManager;
    private PlaceholderCache     placeholderCache;
    private ClientVersionManager clientVersionManager;
    private NameTagProvider      nameTagProvider;
    private MenuManager          menuManager;
    private RoundStatsManager    roundStatsManager;
    private CosmeticsManager     cosmeticsManager;
    private NameStyleManager     nameStyleManager;
    private SilverManager        silverManager;
    private ElytraCosmeticManager elytraCosmeticManager;
    private VanillaKitManager    vanillaKitManager;
    private com.scorepvp.hats.HatManager hatManager;
    private com.scorepvp.hats.HatListener hatListener;
    private com.scorepvp.hats.HatGUI hatGUI;
    private DuelScoreboardManager duelScoreboardManager;
    private ColorsManager colorsManager;

    // ── Custom Items ──────────────────────────────────────────────────────────
    private CustomItemRegistry customItemRegistry;

    private MenuEditorListener menuEditorListener;

    // ── GUIs ──────────────────────────────────────────────────────────────────
    private MainMenuGUI   mainMenuGUI;
    private SettingsGUI   settingsGUI;
    private DuelModeGUI   duelModeGUI;
    private DuelRoundsGUI duelRoundsGUI;
    private PartyFfaGUI   partyFfaGUI;
    private ProfileGUI    profileGUI;
    private CosmeticsGUI  cosmeticsGUI;
    private ArmorTrimGUI  armorTrimGUI;
    private KitMenuGUI    kitMenuGUI;
    private KitEditorGUI  kitEditorGUI;
    private TierTagGUI    tierTagGUI;
    private NameColorGUI  nameColorGUI;
    private BadgeGUI      badgeGUI;
    private BadgeColorGUI badgeColorGUI;
    private VanillaKitGUI vanillaKitGUI;
    private EnderKitGUI   enderKitGUI;

    // ── onEnable ──────────────────────────────────────────────────────────────
    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String r : new String[]{
            "modes.yml","lang/ru.yml","cosmetics.yml",
            "kits/vanilla.yml","kits/axe.yml","kits/uhc.yml",
            "kits/pot.yml","kits/nodebuff.yml",
            "item_menus/duel.yml","item_menus/party.yml","item_menus/profile.yml",
            "item_menus/main_menu.yml","item_menus/settings.yml","item_menus/duel_rounds.yml",
            "item_menus/kit_menu.yml","item_menus/kit_editor.yml","item_menus/cosmetics_armor.yml",
            "item_menus/ender_kit.yml",
            "item_menus/tags_select.yml","item_menus/tags_confirm.yml",
            "namecolors/standart.yml","namecolors/premium.yml","nametags.yml",
            "hats.yml","scoreboard.yml","colors.yml"
        }) saveResourceIfAbsent(r);

        configManager        = new ConfigManager(this);
        storageService       = new StorageService(this);

        playerDataManager     = new PlayerDataManager();
        clientVersionManager  = new ClientVersionManager(this);
        hotbarManager        = new HotbarManager(this);
        partyManager         = new PartyManager(this);
        tierManager          = new TierManager(this);
        settingsManager      = new SettingsManager(this);
        colorsManager        = new ColorsManager(this);
        kitManager           = new KitManager(this);
        arenaManager         = new ArenaManager(this);
        arenaRestoreManager  = new ArenaRestoreManager(this);
        arenaBlockTracker    = new ArenaBlockTracker(this);
        statsManager         = new StatsManager(this);
        matchManager         = new MatchManager(this);
        duelScoreboardManager = new DuelScoreboardManager(this);

        // ── NameTag Provider (TAB / LuckPerms auto-detection) ────────────
        LuckPermsHook luckPermsHook = null;
        if (getServer().getPluginManager().getPlugin("LuckPerms") != null) {
            try {
                luckPermsHook = new LuckPermsHook();
                getLogger().info("[ScorePvP] LuckPerms detected — prefix support enabled.");
            } catch (Exception e) {
                getLogger().warning("[ScorePvP] LuckPerms found but failed to hook: " + e.getMessage());
            }
        }
        if (getServer().getPluginManager().getPlugin("TAB") != null) {
            nameTagProvider = new TabNameTagProvider(this, luckPermsHook);
            getLogger().info("[ScorePvP] TAB detected — using TAB nametag provider.");
            // Register TAB reload listener to re-apply nametags after /tab reload
            try {
                me.neznamy.tab.api.TabAPI.getInstance().getEventBus()
                        .register(me.neznamy.tab.api.event.plugin.TabLoadEvent.class,
                                event -> nameTagProvider.onExternalReload());
                getLogger().info("[ScorePvP] TAB reload listener registered.");
            } catch (Exception e) {
                getLogger().warning("[ScorePvP] Failed to register TAB reload listener: " + e.getMessage());
            }
            // Warn about potential BelowName conflict
            if (getConfig().getBoolean("nametag.health-below-name", true)) {
                getLogger().warning("[ScorePvP] Health below name is enabled. "
                        + "If TAB's BelowName feature is also enabled, there will be a conflict. "
                        + "Disable either TAB's BelowName or set nametag.health-below-name: false.");
            }
        } else {
            nameTagProvider = new BukkitNameTagProvider(this, luckPermsHook);
            getLogger().info("[ScorePvP] TAB not found — using Bukkit nametag provider.");
        }

        duelManager          = new DuelManager(this);
        msgManager           = new MsgManager(this);
        friendsManager       = new FriendsManager(this);
        placeholderCache     = new PlaceholderCache(this);
        customItemRegistry   = new CustomItemRegistry();
        customItemRegistry.setPlugin(this);
        menuManager          = new MenuManager(this);
        roundStatsManager    = new RoundStatsManager(this);
        cosmeticsManager     = new CosmeticsManager(this);
        nameStyleManager     = new NameStyleManager(this);
        silverManager        = new SilverManager(this);
        elytraCosmeticManager = new ElytraCosmeticManager(this);
        vanillaKitManager    = new VanillaKitManager(this);
        hatManager           = new com.scorepvp.hats.HatManager(this);
        hatGUI               = new com.scorepvp.hats.HatGUI(this);

        arenaManager.loadAll();
        placeholderCache.init();

        mainMenuGUI   = new MainMenuGUI(this);
        settingsGUI   = new SettingsGUI(this);
        duelModeGUI   = new DuelModeGUI(this);
        duelRoundsGUI = new DuelRoundsGUI(this);
        partyFfaGUI   = new PartyFfaGUI(this);
        profileGUI    = new ProfileGUI(this);
        cosmeticsGUI  = new CosmeticsGUI(this);
        armorTrimGUI  = new ArmorTrimGUI(this);
        kitMenuGUI    = new KitMenuGUI(this);
        kitEditorGUI  = new KitEditorGUI(this);
        tierTagGUI    = new TierTagGUI(this);
        nameColorGUI  = new NameColorGUI(this);
        badgeGUI      = new BadgeGUI(this);
        badgeColorGUI = new BadgeColorGUI(this);
        vanillaKitGUI = new VanillaKitGUI(this);
        enderKitGUI   = new EnderKitGUI(this);

        // ── Listeners ─────────────────────────────────────────────────────────
        var pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerJoinQuitListener(this),     this);
        pm.registerEvents(new InventoryProtectListener(this),   this);
        pm.registerEvents(new HotbarInteractListener(this),     this);
        pm.registerEvents(new GUIClickListener(this),           this);
        pm.registerEvents(new PartyChatListener(this),          this);
        pm.registerEvents(new SpectatorChatListener(this),      this);
        pm.registerEvents(new VanillaRegionListener(this),      this);
        pm.registerEvents(new PlayerDeathRespawnListener(this), this);
        pm.registerEvents(new MatchListener(this),              this);
        pm.registerEvents(new ArenaToolListener(this),          this);
        // Section 3: command restriction
        pm.registerEvents(new CommandBlockListener(this),       this);
        // Section 5: custom item (golden head)
        pm.registerEvents(new CustomItemListener(this),         this);
        // Round combat stats + action bar HUD
        pm.registerEvents(new CombatStatsListener(this),        this);
        // Cosmetics GUIs
        pm.registerEvents(new CosmeticsListener(this),          this);
        pm.registerEvents(new TierTagListener(this),            this);
        pm.registerEvents(new NameColorListener(this),          this);
        pm.registerEvents(new BadgeListener(this),              this);
        pm.registerEvents(new BadgeColorListener(this),         this);
        pm.registerEvents(new ElytraCosmeticListener(this),     this);
        pm.registerEvents(new VanillaKitListener(this),         this);
        pm.registerEvents(new EnderKitListener(this),           this);
        hatListener = new com.scorepvp.hats.HatListener(this);
        pm.registerEvents(hatListener,                          this);
        pm.registerEvents(arenaBlockTracker,                    this);
        // Dynamic menu editor
        menuEditorListener = new MenuEditorListener(this);
        pm.registerEvents(menuEditorListener,                   this);

        // ── Commands ──────────────────────────────────────────────────────────
        regCmd("scorepvp", new ScorePvPCommand(this));
        regCmd("party",    new PartyCommand(this));
        regCmd("kit",      new KitCommand(this));
        regCmd("tier",     new TierCommand(this));
        regCmd("friends",  new FriendsCommand(this));
        regCmd("cosmetics", new com.scorepvp.command.CosmeticsCommand(this));
        // /tiertags and /hats are gone — both are now categories inside /cosmetics
        // (tiertag at slot 12, hats at slot 13).
        regCmd("kumush",    new com.scorepvp.command.SilverCommand(this));
        CommandRegistry.register(this);

        // ── PlaceholderAPI ────────────────────────────────────────────────────
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new ScorePvPExpansion(this).register();
            getLogger().info("[ScorePvP] PlaceholderAPI expansion registered.");
        } else {
            getLogger().warning("[ScorePvP] PlaceholderAPI not found — placeholders disabled.");
        }

        // ── Tasks ─────────────────────────────────────────────────────────────
        new HotbarIntegrityTask(this).runTaskTimer(this, 60L, 60L);
        new PlaytimeTask(this).runTaskTimer(this, 6000L, 6000L);
        long watchdogTicks = getConfig().getLong("arena.stuck-arena-check-minutes", 5) * 60L * 20L;
        new com.scorepvp.task.ArenaWatchdogTask(this).runTaskTimer(this, watchdogTicks, watchdogTicks);

        getLogger().info("[ScorePvP] ✔ Loaded v" + getDescription().getVersion());
    }

    // ── onDisable ─────────────────────────────────────────────────────────────
    @Override
    public void onDisable() {
        // 0. Tear down every active match FIRST: restore vanilla arenas, cancel
        //    all duels/queues, and teleport everyone back to spawn. Done inline
        //    (the scheduler is already dead at this point) so a restart never
        //    leaves arenas griefed or players trapped in spectator.
        if (duelManager != null) duelManager.clearAllQueues();
        if (matchManager != null) matchManager.shutdownAllMatches();

        // 1. Synchronous flush of ALL stats and playtime ONCE (before per-player cleanup)
        if (statsManager != null) statsManager.flushAllSync();

        // 2. Per-player cleanup (unload caches)
        getServer().getOnlinePlayers().forEach(p -> {
            java.util.UUID uuid = p.getUniqueId();
            if (settingsManager != null) settingsManager.unload(uuid);
            if (friendsManager != null)  friendsManager.unload(uuid);
            if (playerDataManager != null) playerDataManager.remove(uuid);
        });

        // 3. Sync-save all arenas before shutdown (prevents data loss on restart).
        //    Arenas are file-based now, so this is just a few yml writes.
        if (storageService != null && arenaManager != null) {
            java.io.File dir = storageService.getArenaDir();
            for (com.scorepvp.model.Arena arena : arenaManager.getAllArenas()) {
                try {
                    storageService.getArenaFiles().save(arena, dir);
                } catch (Exception e) {
                    getLogger().severe("[ScorePvP] Failed to save arena #" + arena.getNumber() + " on shutdown: " + e.getMessage());
                }
            }
        }

        // 4. Shutdown storage pools last
        if (storageService != null) storageService.shutdown();
        getLogger().info("[ScorePvP] Disabled.");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private void saveResourceIfAbsent(String path) {
        if (new File(getDataFolder(), path).exists()) return;
        try { saveResource(path, false); }
        catch (IllegalArgumentException ignored) { /* missing in jar */ }
    }
    private void regCmd(String name, Object handler) {
        var cmd = getCommand(name);
        if (cmd == null) { getLogger().warning("Command /" + name + " not in plugin.yml"); return; }
        if (handler instanceof org.bukkit.command.CommandExecutor ce) cmd.setExecutor(ce);
        if (handler instanceof org.bukkit.command.TabCompleter tc)    cmd.setTabCompleter(tc);
    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public StorageService       getStorage()               { return storageService; }
    public ConfigManager        getConfigManager()         { return configManager; }
    public HotbarManager        getHotbarManager()         { return hotbarManager; }
    public PartyManager         getPartyManager()          { return partyManager; }
    public TierManager          getTierManager()           { return tierManager; }
    public SettingsManager      getSettingsManager()       { return settingsManager; }
    public KitManager           getKitManager()            { return kitManager; }
    public PlayerDataManager    getPlayerDataManager()     { return playerDataManager; }
    public ArenaManager         getArenaManager()          { return arenaManager; }
    public ArenaRestoreManager  getArenaRestoreManager()   { return arenaRestoreManager; }
    public ArenaBlockTracker    getArenaBlockTracker()     { return arenaBlockTracker; }
    public MatchManager         getMatchManager()          { return matchManager; }
    public DuelScoreboardManager getDuelScoreboardManager() { return duelScoreboardManager; }
    public ColorsManager        getColorsManager()          { return colorsManager; }
    public DuelManager          getDuelManager()           { return duelManager; }
    public StatsManager         getStatsManager()          { return statsManager; }
    public MsgManager           getMsgManager()            { return msgManager; }
    public FriendsManager       getFriendsManager()        { return friendsManager; }
    public PlaceholderCache      getPlaceholderCache()      { return placeholderCache; }
    public ClientVersionManager getClientVersionManager()  { return clientVersionManager; }
    public NameTagProvider      getNameTagProvider()       { return nameTagProvider; }
    public CustomItemRegistry   getCustomItemRegistry()    { return customItemRegistry; }
    public MainMenuGUI          getMainMenuGUI()           { return mainMenuGUI; }
    public SettingsGUI          getSettingsGUI()           { return settingsGUI; }
    public KitMenuGUI           getKitMenuGUI()            { return kitMenuGUI; }
    public KitEditorGUI         getKitEditorGUI()          { return kitEditorGUI; }
    public TierTagGUI           getTierTagGUI()            { return tierTagGUI; }
    public NameColorGUI         getNameColorGUI()          { return nameColorGUI; }
    public BadgeGUI             getBadgeGUI()              { return badgeGUI; }
    public BadgeColorGUI        getBadgeColorGUI()         { return badgeColorGUI; }
    public DuelModeGUI          getDuelModeGUI()           { return duelModeGUI; }
    public DuelRoundsGUI        getDuelRoundsGUI()         { return duelRoundsGUI; }
    public PartyFfaGUI          getPartyFfaGUI()           { return partyFfaGUI; }
    public ProfileGUI           getProfileGUI()            { return profileGUI; }
    public CosmeticsGUI         getCosmeticsGUI()          { return cosmeticsGUI; }
    public ArmorTrimGUI         getArmorTrimGUI()          { return armorTrimGUI; }
    public MenuManager          getMenuManager()           { return menuManager; }
    public MenuEditorListener   getMenuEditorListener()    { return menuEditorListener; }
    public RoundStatsManager    getRoundStatsManager()     { return roundStatsManager; }
    public CosmeticsManager     getCosmeticsManager()      { return cosmeticsManager; }
    public NameStyleManager     getNameStyleManager()      { return nameStyleManager; }
    public SilverManager        getSilverManager()         { return silverManager; }
    public ElytraCosmeticManager getElytraCosmeticManager() { return elytraCosmeticManager; }
    public VanillaKitManager    getVanillaKitManager()     { return vanillaKitManager; }
    public VanillaKitGUI        getVanillaKitGUI()         { return vanillaKitGUI; }
    public EnderKitGUI          getEnderKitGUI()           { return enderKitGUI; }
    public com.scorepvp.hats.HatManager  getHatManager()  { return hatManager; }
    public com.scorepvp.hats.HatListener getHatListener() { return hatListener; }
    public com.scorepvp.hats.HatGUI      getHatGUI()      { return hatGUI; }
}
