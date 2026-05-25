package com.scorepvp;

import com.scorepvp.command.*;
import com.scorepvp.compat.BukkitNameTagProvider;
import com.scorepvp.compat.LuckPermsHook;
import com.scorepvp.compat.NameTagProvider;
import com.scorepvp.compat.TabNameTagProvider;
import com.scorepvp.database.DatabaseManager;
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
    private DatabaseManager      databaseManager;
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

    // ── onEnable ──────────────────────────────────────────────────────────────
    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String r : new String[]{
            "modes.yml","lang/ru.yml","lang/uz.yml","menus.yml","cosmetics.yml",
            "kits/vanilla.yml","kits/axe.yml","kits/uhc.yml",
            "kits/pot.yml","kits/nodebuff.yml",
            "item_menus/duel.yml","item_menus/party.yml","item_menus/profile.yml",
            "item_menus/main_menu.yml","item_menus/settings.yml","item_menus/duel_rounds.yml"
        }) saveResourceIfAbsent(r);

        configManager        = new ConfigManager(this);
        databaseManager      = new DatabaseManager(this);
        databaseManager.initialize();

        playerDataManager     = new PlayerDataManager();
        clientVersionManager  = new ClientVersionManager(this);
        hotbarManager        = new HotbarManager(this);
        partyManager         = new PartyManager(this);
        tierManager          = new TierManager(this);
        settingsManager      = new SettingsManager(this);
        kitManager           = new KitManager(this);
        arenaManager         = new ArenaManager(this);
        arenaRestoreManager  = new ArenaRestoreManager(this);
        arenaBlockTracker    = new ArenaBlockTracker(this);
        statsManager         = new StatsManager(this);
        matchManager         = new MatchManager(this);

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

        arenaManager.loadAll();
        placeholderCache.init();

        mainMenuGUI   = new MainMenuGUI(this);
        settingsGUI   = new SettingsGUI(this);
        duelModeGUI   = new DuelModeGUI(this);
        duelRoundsGUI = new DuelRoundsGUI(this);
        partyFfaGUI   = new PartyFfaGUI(this);
        profileGUI    = new ProfileGUI(this);
        cosmeticsGUI  = new CosmeticsGUI(this);

        // ── Listeners ─────────────────────────────────────────────────────────
        var pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerJoinQuitListener(this),     this);
        pm.registerEvents(new InventoryProtectListener(this),   this);
        pm.registerEvents(new HotbarInteractListener(this),     this);
        pm.registerEvents(new GUIClickListener(this),           this);
        pm.registerEvents(new PartyChatListener(this),          this);
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
        // 1. Synchronous flush of ALL stats and playtime ONCE (before per-player cleanup)
        if (statsManager != null) statsManager.flushAllSync();

        // 2. Per-player cleanup (unload caches)
        getServer().getOnlinePlayers().forEach(p -> {
            java.util.UUID uuid = p.getUniqueId();
            if (settingsManager != null) settingsManager.unload(uuid);
            if (friendsManager != null)  friendsManager.unload(uuid);
            if (playerDataManager != null) playerDataManager.remove(uuid);
        });

        // 3. Sync-save all arenas before closing the database (prevents data loss on restart)
        if (databaseManager != null && databaseManager.get() != null) {
            for (com.scorepvp.model.Arena arena : arenaManager.getAllArenas()) {
                try {
                    databaseManager.get().saveArena(arena);
                } catch (Exception e) {
                    getLogger().severe("[ScorePvP] Failed to save arena #" + arena.getNumber() + " on shutdown: " + e.getMessage());
                }
            }
        }

        // 4. Shutdown DB pool last
        if (databaseManager != null) databaseManager.shutdown();
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
    public DatabaseManager      getDatabase()              { return databaseManager; }
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
    public DuelModeGUI          getDuelModeGUI()           { return duelModeGUI; }
    public DuelRoundsGUI        getDuelRoundsGUI()         { return duelRoundsGUI; }
    public PartyFfaGUI          getPartyFfaGUI()           { return partyFfaGUI; }
    public ProfileGUI           getProfileGUI()            { return profileGUI; }
    public CosmeticsGUI         getCosmeticsGUI()          { return cosmeticsGUI; }
    public MenuManager          getMenuManager()           { return menuManager; }
    public MenuEditorListener   getMenuEditorListener()    { return menuEditorListener; }
    public RoundStatsManager    getRoundStatsManager()     { return roundStatsManager; }
    public CosmeticsManager     getCosmeticsManager()      { return cosmeticsManager; }
}
