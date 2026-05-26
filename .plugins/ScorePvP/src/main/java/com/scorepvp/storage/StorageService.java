package com.scorepvp.storage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Single entry point for persistence. Owns three things:
 *
 * <ul>
 *   <li>{@link TierStorage} — tiers only. SQLite ({@code tiers.db}) or MySQL,
 *       chosen by {@code storage.tiers.type} in config.yml.</li>
 *   <li>{@link MainStorage} — stats / playtime / settings / friends.
 *       Always SQLite ({@code scorepvp.db}).</li>
 *   <li>{@link ArenaFileStore} — arena YAML files under {@code data-arena/}.</li>
 * </ul>
 *
 * <h2>Why three handles, not one</h2>
 * Each storage has a different lifecycle and a different blast radius:
 * tiers can live on a shared MySQL host across a network of servers; stats
 * are local per-server (and small); arenas are version-controllable YAML.
 * Lumping them together is exactly the mistake the old monolith made — and
 * the reason {@code data.db} ballooned to 500MB.
 *
 * <h2>MySQL fallback</h2>
 * If {@code storage.tiers.type: mysql} fails to connect at startup, we fall
 * back to SQLite tiers and log a SEVERE warning. The server keeps running —
 * losing tier sync is preferable to refusing to boot.
 */
public class StorageService {

    private final JavaPlugin plugin;
    private final Logger log;

    private final TierStorage     tiers;
    private final MainStorage     main;
    private final ArenaFileStore  arenaFiles;
    private final File            arenaDir;
    private final File            playerKitDir;

    public StorageService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log    = plugin.getLogger();

        File data = plugin.getDataFolder();
        if (!data.exists() && !data.mkdirs()) {
            log.warning("[ScorePvP] plugin data folder could not be created");
        }

        // Quietly nudge admins forward off old `database:` configs.
        migrateLegacyConfigSection();

        // ── Tiers: SQLite or MySQL ───────────────────────────────────────────
        ConfigurationSection sCfg = plugin.getConfig().getConfigurationSection("storage");
        String tierType = (sCfg != null && sCfg.getConfigurationSection("tiers") != null)
                ? sCfg.getConfigurationSection("tiers").getString("type", "sqlite")
                : "sqlite";

        TierStorage tiersImpl = null;
        if ("mysql".equalsIgnoreCase(tierType)) {
            try {
                ConfigurationSection mysqlCfg = sCfg.getConfigurationSection("tiers")
                        .getConfigurationSection("mysql");
                tiersImpl = new MysqlTierStorage(mysqlCfg, log);
                log.info("[ScorePvP] Tier storage: MySQL");
            } catch (SQLException e) {
                log.severe("[ScorePvP] Tier MySQL connection failed: " + e.getMessage()
                        + " — falling back to SQLite tiers.db");
                tiersImpl = new SqliteTierStorage(data, log);
            }
        } else {
            tiersImpl = new SqliteTierStorage(data, log);
            log.info("[ScorePvP] Tier storage: SQLite (tiers.db)");
        }
        this.tiers = tiersImpl;

        // ── Main: always SQLite ──────────────────────────────────────────────
        this.main = new SqliteMainStorage(data, log);
        log.info("[ScorePvP] Main storage: SQLite (scorepvp.db)");

        // ── Arenas: file-based ───────────────────────────────────────────────
        this.arenaDir     = new File(data, "data-arena");
        this.playerKitDir = new File(data, "data-player");
        if (!arenaDir.exists() && !arenaDir.mkdirs()) {
            log.warning("[ScorePvP] data-arena/ could not be created");
        }
        if (!playerKitDir.exists() && !playerKitDir.mkdirs()) {
            log.warning("[ScorePvP] data-player/ could not be created");
        }
        this.arenaFiles = new ArenaFileStore(log);

        // ── One-shot housekeeping ────────────────────────────────────────────
        renameLegacyMonolithDb();
        migrateLegacyPlayerKitsDir();
    }

    public TierStorage     getTiers()       { return tiers; }
    public MainStorage     getMain()        { return main; }
    public ArenaFileStore  getArenaFiles()  { return arenaFiles; }
    public File            getArenaDir()    { return arenaDir; }
    public File            getPlayerKitDir(){ return playerKitDir; }

    /** Closes both DB pools. Safe to call multiple times. */
    public void shutdown() {
        try { if (tiers != null) tiers.close(); }
        catch (Exception e) { log.warning("[ScorePvP] tiers.close() failed: " + e.getMessage()); }
        try { if (main != null) main.close(); }
        catch (Exception e) { log.warning("[ScorePvP] main.close() failed: " + e.getMessage()); }
    }

    // ── Compat / housekeeping ────────────────────────────────────────────────

    /**
     * If the user is still on the old single-DB config layout
     * ({@code database.type}), copy the type into {@code storage.tiers.type}
     * and warn. Doesn't touch the rest of the new config — fresh defaults are
     * better than partial migration.
     */
    private void migrateLegacyConfigSection() {
        ConfigurationSection legacy = plugin.getConfig().getConfigurationSection("database");
        if (legacy == null) return;
        if (plugin.getConfig().getConfigurationSection("storage") != null) return;

        String type = legacy.getString("type", "sqlite");
        log.warning("[ScorePvP] config.yml has legacy `database:` section. "
                + "Auto-migrating tier type → `storage.tiers.type: " + type + "`. "
                + "Please update your config.yml — see README.");
        plugin.getConfig().set("storage.tiers.type", type);
        if ("mysql".equalsIgnoreCase(type)) {
            plugin.getConfig().set("storage.tiers.mysql.host",
                    legacy.getString("host", "localhost"));
            plugin.getConfig().set("storage.tiers.mysql.port",
                    legacy.getInt("port", 3306));
            plugin.getConfig().set("storage.tiers.mysql.database",
                    legacy.getString("database", "scorepvp_tiers"));
            plugin.getConfig().set("storage.tiers.mysql.username",
                    legacy.getString("username", "root"));
            plugin.getConfig().set("storage.tiers.mysql.password",
                    legacy.getString("password", ""));
        }
        plugin.getConfig().set("database", null);
        plugin.saveConfig();
    }

    /**
     * If the old monolithic {@code data.db} is sitting in the data folder,
     * rename it once so it doesn't get re-used. We never auto-migrate its
     * data — per project decision, the rewrite starts from a clean slate.
     */
    private void renameLegacyMonolithDb() {
        File old = new File(plugin.getDataFolder(), "data.db");
        if (!old.exists()) return;
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
        File renamed = new File(plugin.getDataFolder(), "data.db.old." + stamp);
        if (old.renameTo(renamed)) {
            log.warning("[ScorePvP] Legacy data.db renamed to " + renamed.getName()
                    + " — not migrated (fresh start by design).");
        } else {
            log.warning("[ScorePvP] Could not rename legacy data.db — leave as-is.");
        }
    }

    /**
     * If the previous version stored personal kits in {@code data/<uuid>.yml},
     * lift them into {@code data-player/} so players don't lose their kits on
     * upgrade. We only move when {@code data-player/} is empty — never clobber.
     */
    private void migrateLegacyPlayerKitsDir() {
        File legacy = new File(plugin.getDataFolder(), "data");
        if (!legacy.isDirectory()) return;
        File[] files = legacy.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null || files.length == 0) return;

        File[] already = playerKitDir.listFiles();
        if (already != null && already.length > 0) return; // don't clobber

        int moved = 0;
        for (File f : files) {
            File dst = new File(playerKitDir, f.getName());
            if (f.renameTo(dst)) moved++;
        }
        if (moved > 0) {
            log.info("[ScorePvP] Migrated " + moved + " personal-kit file(s) "
                    + "from data/ → data-player/.");
        }
    }
}
