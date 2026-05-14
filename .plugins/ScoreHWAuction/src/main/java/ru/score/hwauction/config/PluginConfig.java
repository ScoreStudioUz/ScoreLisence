package ru.score.hwauction.config;

import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import ru.score.hwauction.ScoreHWAuction;

public final class PluginConfig {

    private final ScoreHWAuction plugin;

    // --- core settings ---
    private int     defaultMaxLots;
    private double  tax;
    private double  minPrice;
    private double  maxPrice;
    private int     defaultDurationHours;
    private int     auctionMinHours;
    private int     auctionMaxHours;
    private int     itemsPerPage;
    private boolean allowPerUnit;
    private boolean enableSounds;
    private boolean broadcastPurchase;
    private boolean allowSelfBuy;
    private double  autoPriceMultiplier;
    private double  defaultStepPercent;
    private String  currencySymbol;
    private boolean debugMode;

    // --- new feature flags ---
    private boolean enableSearch;
    private boolean enablePlayerFilter;
    private boolean enableBidAuction;
    private boolean enableAutoSell;
    private boolean enableExpiredCleaner;
    private long    expiredCleanupDelayHours;  // how long item sits in expired before auto-delete

    // --- anti-dupe / lag protection ---
    private int     maxClickCooldownMs;

    // --- bid extension ---
    private boolean bidTimeExtensionEnabled;
    private int     bidTimeExtensionSeconds;   // seconds added per bid
    private int     bidMaxStepsAhead;          // max steps a bidder can jump (e.g. 5 = 5x step)
    private int     bidMaxStepsAheadCap;       // hard cap on max bid steps

    // --- auto-sell ---
    private double  autoSellMarkupPercent;     // how much above cheapest price to list
    private int     autoSellConfirmTimeoutSec; // how long confirm pending lasts

    public PluginConfig(ScoreHWAuction plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        defaultMaxLots     = plugin.getConfig().getInt("auction.default-max-lots", 5);
        tax                = plugin.getConfig().getDouble("auction.tax", 0.05);
        minPrice           = plugin.getConfig().getDouble("auction.min-price", 1.0);
        maxPrice           = plugin.getConfig().getDouble("auction.max-price", 999_999_999.0);
        defaultDurationHours = plugin.getConfig().getInt("auction.default-duration-hours", 48);
        auctionMinHours    = plugin.getConfig().getInt("auction.auction-min-time", 1);
        auctionMaxHours    = plugin.getConfig().getInt("auction.auction-max-time", 72);
        itemsPerPage       = plugin.getConfig().getInt("auction.items-per-page", 45);
        allowPerUnit       = plugin.getConfig().getBoolean("auction.allow-per-unit", true);
        enableSounds       = plugin.getConfig().getBoolean("auction.enable-sounds", true);
        broadcastPurchase  = plugin.getConfig().getBoolean("auction.broadcast-purchase", false);
        allowSelfBuy       = plugin.getConfig().getBoolean("auction.allow-self-buy", false);
        autoPriceMultiplier= plugin.getConfig().getDouble("auction.auto-price-multiplier", 1.0);
        defaultStepPercent = plugin.getConfig().getDouble("auction.default-step-percent", 10.0);
        currencySymbol     = plugin.getConfig().getString("auction.currency-symbol", "α");
        debugMode          = plugin.getConfig().getBoolean("debug", false);

        // Feature flags
        enableSearch          = plugin.getConfig().getBoolean("features.search", true);
        enablePlayerFilter    = plugin.getConfig().getBoolean("features.player-filter", true);
        enableBidAuction      = plugin.getConfig().getBoolean("features.bid-auction", true);
        enableAutoSell        = plugin.getConfig().getBoolean("features.auto-sell", true);
        enableExpiredCleaner  = plugin.getConfig().getBoolean("features.expired-cleaner", true);
        expiredCleanupDelayHours = plugin.getConfig().getLong("features.expired-cleanup-delay-hours", 72);

        // Click cooldown (anti-spam)
        maxClickCooldownMs   = plugin.getConfig().getInt("protection.click-cooldown-ms", 250);

        // Bid extension
        bidTimeExtensionEnabled = plugin.getConfig().getBoolean("auction.bid-time-extension.enabled", true);
        bidTimeExtensionSeconds = plugin.getConfig().getInt("auction.bid-time-extension.seconds", 30);
        bidMaxStepsAhead     = plugin.getConfig().getInt("auction.bid-steps-ahead", 5);
        bidMaxStepsAheadCap  = plugin.getConfig().getInt("auction.bid-steps-ahead-cap", 10);

        // Auto-sell
        autoSellMarkupPercent   = plugin.getConfig().getDouble("auction.auto-sell-markup-percent", 5.0);
        autoSellConfirmTimeoutSec = plugin.getConfig().getInt("auction.auto-sell-confirm-timeout", 30);

        // Sanity
        if (tax < 0 || tax > 1) { plugin.getLogger().warning("auction.tax must be 0-1; reset to 0.05."); tax = 0.05; }
        if (itemsPerPage < 1 || itemsPerPage > 45) { itemsPerPage = 45; }
        if (minPrice < 0) minPrice = 0;
        if (maxPrice < minPrice) maxPrice = 999_999_999.0;
        if (auctionMinHours < 1) auctionMinHours = 1;
        if (auctionMaxHours < auctionMinHours) auctionMaxHours = 72;
        if (maxClickCooldownMs < 0) maxClickCooldownMs = 0;
        if (bidTimeExtensionSeconds < 0) bidTimeExtensionSeconds = 0;
        if (bidMaxStepsAhead < 1) bidMaxStepsAhead = 1;
        if (bidMaxStepsAheadCap < bidMaxStepsAhead) bidMaxStepsAheadCap = bidMaxStepsAhead * 2;
        if (expiredCleanupDelayHours < 1) expiredCleanupDelayHours = 1;
    }

    public int     getDefaultMaxLots()              { return defaultMaxLots; }

    /**
     * Resolves max auction slots for a player by scanning scoreah.maxlots.N permissions.
     * The highest N found wins. Falls back to config default-max-lots.
     * Example: scoreah.maxlots.5 → 5 slots, scoreah.maxlots.10 → 10 slots.
     */
    public int getMaxLotsForPlayer(Player player) {
        int max = -1;
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) continue;
            String perm = info.getPermission();
            if (perm.startsWith("scoreah.maxlots.")) {
                try {
                    int n = Integer.parseInt(perm.substring("scoreah.maxlots.".length()));
                    if (n > max) max = n;
                } catch (NumberFormatException ignored) {}
            }
        }
        return max > 0 ? max : defaultMaxLots;
    }
    public double  getTax()                        { return tax; }
    public double  getMinPrice()                   { return minPrice; }
    public double  getMaxPrice()                   { return maxPrice; }
    public int     getDefaultDurationHours()       { return defaultDurationHours; }
    public int     getAuctionMinHours()            { return auctionMinHours; }
    public int     getAuctionMaxHours()            { return auctionMaxHours; }
    public int     getItemsPerPage()               { return itemsPerPage; }
    public boolean isAllowPerUnit()                { return allowPerUnit; }
    public boolean isEnableSounds()                { return enableSounds; }
    public boolean isBroadcastPurchase()           { return broadcastPurchase; }
    public boolean isAllowSelfBuy()                { return allowSelfBuy; }
    public double  getAutoPriceMultiplier()        { return autoPriceMultiplier; }
    public double  getDefaultStepPercent()         { return defaultStepPercent; }
    public String  getCurrencySymbol()             { return currencySymbol; }
    public boolean isDebugMode()                   { return debugMode; }
    public boolean isEnableSearch()                { return enableSearch; }
    public boolean isEnablePlayerFilter()          { return enablePlayerFilter; }
    public boolean isEnableBidAuction()            { return enableBidAuction; }
    public boolean isEnableAutoSell()              { return enableAutoSell; }
    public boolean isEnableExpiredCleaner()        { return enableExpiredCleaner; }
    public long    getExpiredCleanupDelayHours()   { return expiredCleanupDelayHours; }
    public int     getMaxClickCooldownMs()         { return maxClickCooldownMs; }
    public boolean isBidTimeExtensionEnabled()     { return bidTimeExtensionEnabled; }
    public int     getBidTimeExtensionSeconds()    { return bidTimeExtensionSeconds; }
    public int     getBidMaxStepsAhead()           { return bidMaxStepsAhead; }
    public int     getBidMaxStepsAheadCap()        { return bidMaxStepsAheadCap; }
    public double  getAutoSellMarkupPercent()      { return autoSellMarkupPercent; }
    public int     getAutoSellConfirmTimeoutSec()  { return autoSellConfirmTimeoutSec; }
}
