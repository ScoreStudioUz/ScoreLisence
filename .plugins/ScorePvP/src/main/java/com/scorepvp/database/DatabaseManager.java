package com.scorepvp.database;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.PlayerSettings;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DatabaseManager {

    private final ScorePvP plugin;
    private IDatabase active;
    private final ExecutorService async = Executors.newCachedThreadPool();

    public DatabaseManager(ScorePvP plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        String dbType = plugin.getConfig().getString("database.type", "mysql").toLowerCase();

        if (dbType.equals("sqlite")) {
            // Forced SQLite mode
            initSQLite("принудительный режим SQLite (database.type=sqlite)");
            return;
        }

        // MySQL mode (with SQLite fallback)
        MySQLDatabase mysql = new MySQLDatabase(plugin.getConfig(), plugin.getLogger());
        try {
            mysql.connect();
            mysql.createTables();
            active = mysql;
            plugin.getLogger().info("[ScorePvP] База данных: MySQL активна.");
        } catch (Exception e) {
            plugin.getLogger().warning("[ScorePvP] MySQL недоступна (" + e.getMessage() + "), переключение на SQLite...");
            initSQLite("резервный режим (MySQL недоступна)");
        }
    }

    private void initSQLite(String reason) {
        SQLiteDatabase sqlite = new SQLiteDatabase(plugin.getDataFolder(), plugin.getLogger());
        try {
            sqlite.connect();
            sqlite.createTables();
            active = sqlite;
            plugin.getLogger().info("[ScorePvP] База данных: SQLite активна (" + reason + ").");
        } catch (Exception ex) {
            plugin.getLogger().severe("[ScorePvP] КРИТИЧНО: Невозможно подключиться к БД! " + ex.getMessage());
        }
    }

    public void shutdown() {
        async.shutdown();
        try {
            // Wait up to 10 seconds for pending async saves to complete
            if (!async.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                async.shutdownNow();
            }
        } catch (InterruptedException e) {
            async.shutdownNow();
        }
        if (active != null) active.disconnect();
    }

    public IDatabase get() { return active; }

    // ── Async helpers ────────────────────────────────────────────────────────

    public CompletableFuture<PlayerSettings> loadSettingsAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> active.loadSettings(uuid), async);
    }

    public CompletableFuture<Void> saveSettingsAsync(PlayerSettings s) {
        return CompletableFuture.runAsync(() -> active.saveSettings(s), async);
    }

    public CompletableFuture<Map<String, String>> loadTiersAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> active.loadTiers(uuid), async);
    }

    public CompletableFuture<Void> saveTierAsync(UUID uuid, String mode, String tier) {
        return CompletableFuture.runAsync(() -> active.saveTier(uuid, mode, tier), async);
    }

    public CompletableFuture<Void> removeTierAsync(UUID uuid, String mode) {
        return CompletableFuture.runAsync(() -> active.removeTier(uuid, mode), async);
    }

    public CompletableFuture<ItemStack[]> loadPersonalKitAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> active.loadPersonalKit(uuid), async);
    }

    public CompletableFuture<Void> savePersonalKitAsync(UUID uuid, ItemStack[] contents) {
        return CompletableFuture.runAsync(() -> active.savePersonalKit(uuid, contents), async);
    }

    // ── Async Arena helpers ──────────────────────────────────────────────────
    public java.util.concurrent.CompletableFuture<java.util.List<com.scorepvp.model.Arena>> loadAllArenasAsync() {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> active.loadAllArenas(), async);
    }
    public java.util.concurrent.CompletableFuture<Void> saveArenaAsync(com.scorepvp.model.Arena arena) {
        return java.util.concurrent.CompletableFuture.runAsync(() -> active.saveArena(arena), async);
    }
    // ── Async Stats helpers ──────────────────────────────────────────────────
    public java.util.concurrent.CompletableFuture<com.scorepvp.model.PlayerStats> loadStatsAsync(java.util.UUID uuid, String mode) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> active.loadStats(uuid, mode), async);
    }
    public java.util.concurrent.CompletableFuture<Void> saveStatsAsync(com.scorepvp.model.PlayerStats stats) {
        return java.util.concurrent.CompletableFuture.runAsync(() -> active.saveStats(stats), async);
    }

    public java.util.concurrent.CompletableFuture<Long> loadPlaytimeAsync(java.util.UUID uuid) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> active.loadPlaytime(uuid), async);
    }

    public java.util.concurrent.CompletableFuture<Void> savePlaytimeAsync(java.util.UUID uuid, long minutes) {
        return java.util.concurrent.CompletableFuture.runAsync(() -> active.savePlaytime(uuid, minutes), async);
    }
}
