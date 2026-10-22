package ru.score.hwauction;

import org.bukkit.plugin.java.JavaPlugin;
import ru.score.hwauction.command.AuctionCommand;
import ru.score.hwauction.config.BlacklistRegistry;
import ru.score.hwauction.config.CategoryRegistry;
import ru.score.hwauction.config.Messages;
import ru.score.hwauction.config.PluginConfig;
import ru.score.hwauction.config.TemplateRegistry;
import ru.score.hwauction.util.RuNameProvider;
import ru.score.hwauction.database.DatabaseFactory;
import ru.score.hwauction.database.DatabaseManager;
import ru.score.hwauction.gui.bid.BidViewGui;
import ru.score.hwauction.gui.buyamount.BuyAmountGui;
import ru.score.hwauction.gui.buyamount.BuyConfirmGui;
import ru.score.hwauction.gui.expired.ExpiredGui;
import ru.score.hwauction.gui.main.MainAuctionGui;
import ru.score.hwauction.gui.myitems.MyItemsGui;
import ru.score.hwauction.listener.AuctionListener;
import ru.score.hwauction.manager.*;
import ru.score.hwauction.task.CacheLoadTask;
import ru.score.hwauction.task.ExpiredCleanupTask;
import ru.score.hwauction.task.ExpiryTask;
import ru.score.hwauction.util.MoneyUtil;
import ru.score.hwauction.util.TimeUtil;

public final class ScoreHWAuction extends JavaPlugin {

    // ---- Config ----
    private PluginConfig      pluginConfig;
    private Messages          messages;
    private CategoryRegistry  categoryRegistry;
    private BlacklistRegistry blacklistRegistry;
    private TemplateRegistry  templateRegistry;
    private RuNameProvider    ruNameProvider;

    // ---- Database ----
    private DatabaseManager db;

    // ---- Economy ----
    private EconomyService economyService;

    // ---- Managers / Services ----
    private AuctionCache        auctionCache;
    private SessionManager      sessionManager;
    private FilterService       filterService;
    private ListingService      listingService;
    private PurchaseService     purchaseService;
    private BidService          bidService;
    private ExpiryService       expiryService;
    private SoundService        soundService;
    private NotificationService notificationService;
    private AutoSellService     autoSellService;
    private PendingConfirmManager pendingConfirmManager;

    // ---- GUIs ----
    private MainAuctionGui mainGui;
    private MyItemsGui     myItemsGui;
    private ExpiredGui     expiredGui;
    private BidViewGui     bidViewGui;
    private BuyAmountGui   buyAmountGui;
    private BuyConfirmGui  buyConfirmGui;

    // ---- Tasks ----
    private CacheLoadTask      cacheLoadTask;
    private ExpiryTask         expiryTask;
    private ExpiredCleanupTask expiredCleanupTask;

    @Override
    public void onEnable() {
        // 1. Config
        pluginConfig      = new PluginConfig(this);
        messages          = new Messages(this);
        categoryRegistry  = new CategoryRegistry(this);
        blacklistRegistry = new BlacklistRegistry(this);
        templateRegistry  = new TemplateRegistry(this);
        ruNameProvider    = new RuNameProvider(this);
        MoneyUtil.setCurrencySymbol(pluginConfig.getCurrencySymbol());
        TimeUtil.reload(messages);

        // 2. Economy (Vault)
        economyService = new EconomyService(this);
        if (!economyService.setup()) {
            getLogger().severe("Vault or an economy plugin was not found! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("Economy hooked: " + economyService.getVaultEconomy().getName());

        // 3. Database
        try {
            db = DatabaseFactory.create(this);
            db.init();
            getLogger().info("Database ready: " + db.getVendorName());
        } catch (Exception e) {
            getLogger().severe("Database initialisation failed: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 4. Services
        auctionCache        = new AuctionCache();
        sessionManager      = new SessionManager(this);
        filterService       = new FilterService(this);
        listingService      = new ListingService(this);
        purchaseService     = new PurchaseService(this);
        bidService          = new BidService(this);
        expiryService       = new ExpiryService(this);
        soundService        = new SoundService(this);
        notificationService = new NotificationService(this);
        pendingConfirmManager = new PendingConfirmManager();
        autoSellService     = new AutoSellService(this);

        // 5. GUIs
        mainGui      = new MainAuctionGui(this);
        myItemsGui   = new MyItemsGui(this);
        expiredGui   = new ExpiredGui(this);
        bidViewGui   = new BidViewGui(this);
        buyAmountGui = new BuyAmountGui(this);
        buyConfirmGui = new BuyConfirmGui(this);

        // 6. Command
        AuctionCommand cmd = new AuctionCommand(this);
        var aCmd = getCommand("ah");
        if (aCmd != null) {
            aCmd.setExecutor(cmd);
            aCmd.setTabCompleter(cmd);
        }

        // 7. Listeners
        getServer().getPluginManager().registerEvents(new AuctionListener(this), this);

        // 8. Tasks
        cacheLoadTask = new CacheLoadTask(this);
        cacheLoadTask.schedule();

        expiryTask = new ExpiryTask(this);
        expiryTask.start();

        expiredCleanupTask = new ExpiredCleanupTask(this);
        expiredCleanupTask.start();

        getLogger().info("ScoreHWAuction v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (expiryTask != null) { try { expiryTask.cancel(); } catch (IllegalStateException ignored) {} }
        if (expiredCleanupTask != null) { try { expiredCleanupTask.cancel(); } catch (IllegalStateException ignored) {} }
        if (db != null) db.close();
        getLogger().info("ScoreHWAuction disabled.");
    }

    // ---- Getters ----
    public PluginConfig         getPluginConfig()          { return pluginConfig; }
    public Messages             getMessages()              { return messages; }
    public CategoryRegistry     getCategoryRegistry()      { return categoryRegistry; }
    public BlacklistRegistry    getBlacklistRegistry()     { return blacklistRegistry; }
    public TemplateRegistry     getTemplateRegistry()      { return templateRegistry; }
    public RuNameProvider       getRuNameProvider()        { return ruNameProvider; }
    public DatabaseManager      getDb()                    { return db; }
    public EconomyService       getEconomyService()        { return economyService; }
    public AuctionCache         getAuctionCache()          { return auctionCache; }
    public SessionManager       getSessionManager()        { return sessionManager; }
    public FilterService        getFilterService()         { return filterService; }
    public ListingService       getListingService()        { return listingService; }
    public PurchaseService      getPurchaseService()       { return purchaseService; }
    public BidService           getBidService()            { return bidService; }
    public ExpiryService        getExpiryService()         { return expiryService; }
    public SoundService         getSoundService()          { return soundService; }
    public NotificationService  getNotificationService()   { return notificationService; }
    public AutoSellService      getAutoSellService()       { return autoSellService; }
    public PendingConfirmManager getPendingConfirmManager(){ return pendingConfirmManager; }
    public MainAuctionGui       getMainGui()               { return mainGui; }
    public MyItemsGui           getMyItemsGui()            { return myItemsGui; }
    public ExpiredGui           getExpiredGui()            { return expiredGui; }
    public BidViewGui           getBidViewGui()            { return bidViewGui; }
    public BuyAmountGui         getBuyAmountGui()          { return buyAmountGui; }
    public BuyConfirmGui        getBuyConfirmGui()         { return buyConfirmGui; }
    public CacheLoadTask        getCacheLoadTask()         { return cacheLoadTask; }
}
