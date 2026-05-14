package ru.score.hwauction.database;

import ru.score.hwauction.ScoreHWAuction;

public final class DatabaseFactory {

    private DatabaseFactory() {}

    public static DatabaseManager create(ScoreHWAuction plugin) {
        String type = plugin.getConfig().getString("database.type", "sqlite").toLowerCase();
        return switch (type) {
            case "mysql" -> new MySQLDatabase(plugin);
            case "sqlite" -> new SQLiteDatabase(plugin);
            default -> {
                plugin.getLogger().warning("Unknown database type '" + type + "', falling back to SQLite.");
                yield new SQLiteDatabase(plugin);
            }
        };
    }
}
