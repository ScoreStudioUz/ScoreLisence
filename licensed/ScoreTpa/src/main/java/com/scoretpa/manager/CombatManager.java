package com.scoretpa.manager;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class CombatManager {

    private final Set<UUID> inCombat = new HashSet<>();

    public void tagCombat(UUID player) {
        inCombat.add(player);
    }

    public boolean isInCombat(UUID player) {
        return inCombat.contains(player);
    }

    public void removeCombat(UUID player) {
        inCombat.remove(player);
    }
}
