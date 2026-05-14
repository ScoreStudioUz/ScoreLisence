package ru.score.hwauction.manager;

import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.model.PlayerSession;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class SessionManager {

    private final ScoreHWAuction plugin;
    private final ConcurrentMap<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();

    public SessionManager(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    /** Get or create a session; loads sound preference asynchronously on first call */
    public PlayerSession getOrCreate(UUID uuid) {
        return sessions.computeIfAbsent(uuid, id -> {
            PlayerSession s = new PlayerSession(id);
            // Load sound preference from DB asynchronously
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    boolean sound = plugin.getDb().loadSoundPreference(id);
                    s.setSoundEnabled(sound);
                } catch (Exception e) {
                    if (plugin.getPluginConfig().isDebugMode()) {
                        plugin.getLogger().warning("Could not load sound pref for " + id + ": " + e.getMessage());
                    }
                }
            });
            return s;
        });
    }

    public PlayerSession getOrCreate(org.bukkit.entity.Player player) {
        return getOrCreate(player.getUniqueId());
    }

    public boolean hasSession(UUID uuid) {
        return sessions.containsKey(uuid);
    }

    public void remove(UUID uuid) {
        sessions.remove(uuid);
    }

    public void clearAll() {
        sessions.clear();
    }
}
