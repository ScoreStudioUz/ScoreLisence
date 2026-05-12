package com.scoretpa.manager;

import com.scoretpa.ScoreTpa;
import com.scoretpa.model.TpaRequest;
import com.scoretpa.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

public class RequestManager {

    private final ScoreTpa plugin;
    private final List<TpaRequest> requests = new ArrayList<>();
    private final Set<UUID> toggledOff = new HashSet<>();

    public RequestManager(ScoreTpa plugin) {
        this.plugin = plugin;
        startExpirationTask();
    }

    public boolean sendRequest(Player sender, Player target) {
        ConfigManager cfg = plugin.getConfigManager();

        if (sender.getUniqueId().equals(target.getUniqueId())) {
            MessageUtil.send(sender, cfg.getMessage("cannot-send-self"));
            return false;
        }

        if (isToggledOff(target.getUniqueId())) {
            MessageUtil.send(sender, cfg.getMessage("teleports-disabled", "%player%", target.getName()));
            return false;
        }

        if (plugin.getCooldownManager().isOnCooldown(sender.getUniqueId())) {
            int remaining = plugin.getCooldownManager().getRemainingCooldown(sender.getUniqueId());
            MessageUtil.send(sender, cfg.getMessage("cooldown-wait", "%cooldown%", String.valueOf(remaining)));
            return false;
        }

        if (hasRequest(sender.getUniqueId(), target.getUniqueId())) {
            MessageUtil.send(sender, cfg.getMessage("already-sent-request"));
            return false;
        }

        if (hasRequest(target.getUniqueId(), sender.getUniqueId())) {
            MessageUtil.send(sender, cfg.getMessage("already-have-request"));
            return false;
        }

        TpaRequest request = new TpaRequest(sender.getUniqueId(), target.getUniqueId());
        requests.add(request);
        plugin.getCooldownManager().setCooldown(sender.getUniqueId());

        MessageUtil.send(sender, cfg.getMessage("request-sent", "%player%", target.getName()));
        MessageUtil.send(target, cfg.getMessage("request-received", "%player%", sender.getName()));
        MessageUtil.sendRequestButtons(sender, target);

        return true;
    }

    public TpaRequest getRequestFrom(UUID sender, UUID target) {
        int expire = plugin.getConfigManager().getExpireTime();
        for (TpaRequest req : requests) {
            if (req.getSender().equals(sender) && req.getTarget().equals(target) && !req.isExpired(expire)) {
                return req;
            }
        }
        return null;
    }

    public TpaRequest getLatestRequestTo(UUID target) {
        int expire = plugin.getConfigManager().getExpireTime();
        TpaRequest latest = null;
        for (TpaRequest req : requests) {
            if (req.getTarget().equals(target) && !req.isExpired(expire)) {
                latest = req;
            }
        }
        return latest;
    }

    public TpaRequest getActiveRequestBySender(UUID sender) {
        int expire = plugin.getConfigManager().getExpireTime();
        for (TpaRequest req : requests) {
            if (req.getSender().equals(sender) && !req.isExpired(expire)) {
                return req;
            }
        }
        return null;
    }

    public void removeRequest(TpaRequest request) {
        requests.remove(request);
    }

    public boolean hasRequest(UUID sender, UUID target) {
        int expire = plugin.getConfigManager().getExpireTime();
        for (TpaRequest req : requests) {
            if (req.getSender().equals(sender) && req.getTarget().equals(target) && !req.isExpired(expire)) {
                return true;
            }
        }
        return false;
    }

    public void removeAllForPlayer(UUID player) {
        Iterator<TpaRequest> it = requests.iterator();
        while (it.hasNext()) {
            TpaRequest r = it.next();
            if (r.getSender().equals(player) || r.getTarget().equals(player)) {
                it.remove();
            }
        }
    }

    public boolean isToggledOff(UUID player) {
        return toggledOff.contains(player);
    }

    public boolean toggle(UUID player) {
        if (toggledOff.contains(player)) {
            toggledOff.remove(player);
            return true; // now enabled
        } else {
            toggledOff.add(player);
            return false; // now disabled
        }
    }

    private void startExpirationTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                int expire = plugin.getConfigManager().getExpireTime();
                ConfigManager cfg = plugin.getConfigManager();
                Iterator<TpaRequest> it = requests.iterator();
                while (it.hasNext()) {
                    TpaRequest req = it.next();
                    if (req.isExpired(expire)) {
                        Player sender = Bukkit.getPlayer(req.getSender());
                        Player target = Bukkit.getPlayer(req.getTarget());
                        if (sender != null) {
                            MessageUtil.send(sender, cfg.getMessage("request-expired"));
                        }
                        if (target != null) {
                            MessageUtil.send(target, cfg.getMessage("request-expired"));
                        }
                        it.remove();
                    }
                }
            }
        }, 20L, 20L);
    }
}
