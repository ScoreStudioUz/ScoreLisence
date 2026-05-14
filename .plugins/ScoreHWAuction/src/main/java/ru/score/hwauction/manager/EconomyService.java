package ru.score.hwauction.manager;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import ru.score.hwauction.ScoreHWAuction;

public final class EconomyService {

    private final ScoreHWAuction plugin;
    private Economy economy;

    public EconomyService(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    public boolean setup() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) return false;
        var rsp = plugin.getServer().getServicesManager()
                .getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    public boolean has(Player player, double amount) {
        if (economy == null) return false;
        return economy.has(player, amount);
    }

    public boolean has(OfflinePlayer player, double amount) {
        if (economy == null) return false;
        return economy.has(player, amount);
    }

    public boolean withdraw(Player player, double amount) {
        if (economy == null || !has(player, amount)) return false;
        economy.withdrawPlayer(player, amount);
        return true;
    }

    public void deposit(OfflinePlayer player, double amount) {
        if (economy == null || amount <= 0) return;
        economy.depositPlayer(player, amount);
    }

    public void deposit(Player player, double amount) {
        if (economy == null || amount <= 0) return;
        economy.depositPlayer(player, amount);
    }

    public double getBalance(OfflinePlayer player) {
        if (economy == null) return 0;
        return economy.getBalance(player);
    }

    public boolean isReady() { return economy != null; }

    public Economy getVaultEconomy() { return economy; }
}
