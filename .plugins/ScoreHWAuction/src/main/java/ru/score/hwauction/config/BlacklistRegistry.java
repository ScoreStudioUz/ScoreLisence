package ru.score.hwauction.config;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import ru.score.hwauction.ScoreHWAuction;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class BlacklistRegistry {

    private final ScoreHWAuction plugin;
    private Set<Material> blacklistedMaterials = EnumSet.noneOf(Material.class);

    public BlacklistRegistry(ScoreHWAuction plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        blacklistedMaterials = EnumSet.noneOf(Material.class);
        List<String> list = plugin.getConfig().getStringList("auction.blacklist");
        for (String s : list) {
            Material m = Material.getMaterial(s.toUpperCase());
            if (m != null) {
                blacklistedMaterials.add(m);
            } else {
                plugin.getLogger().warning("Unknown blacklisted material: " + s);
            }
        }
    }

    public boolean isBlacklisted(ItemStack item) {
        if (item == null || item.getType().isAir()) return true;
        return blacklistedMaterials.contains(item.getType());
    }

    public Set<Material> getBlacklistedMaterials() {
        return Collections.unmodifiableSet(blacklistedMaterials);
    }
}
