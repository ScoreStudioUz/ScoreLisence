package com.scorepvp.compat;

import org.bukkit.Bukkit;

import java.util.UUID;

/**
 * Optional hook into LuckPerms for reading player prefixes.
 * Only instantiated when LuckPerms is present on the server.
 */
public class LuckPermsHook {

    private final net.luckperms.api.LuckPerms api;

    public LuckPermsHook() {
        var provider = Bukkit.getServicesManager().getRegistration(net.luckperms.api.LuckPerms.class);
        if (provider == null) throw new IllegalStateException("LuckPerms not registered");
        this.api = provider.getProvider();
    }

    /**
     * Returns the LuckPerms prefix for a player, or empty string if none.
     */
    public String getPrefix(UUID uuid) {
        var user = api.getUserManager().getUser(uuid);
        if (user == null) return "";
        String prefix = user.getCachedData().getMetaData().getPrefix();
        return prefix != null ? prefix : "";
    }
}
