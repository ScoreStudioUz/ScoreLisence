package com.scorepvp.model;

import java.util.*;

public class Party {

    private UUID leader;
    private final Set<UUID> members = new LinkedHashSet<>();
    private final Set<UUID> chatEnabled = new HashSet<>();

    public Party(UUID leader) {
        this.leader = leader;
        members.add(leader);
    }

    public UUID getLeader()           { return leader; }
    public void setLeader(UUID uuid)  { this.leader = uuid; }

    public Set<UUID> getMembers()     { return Collections.unmodifiableSet(members); }

    public void addMember(UUID uuid)  { members.add(uuid); }
    public void removeMember(UUID uuid) { members.remove(uuid); chatEnabled.remove(uuid); }

    public boolean isMember(UUID uuid)  { return members.contains(uuid); }
    public boolean isLeader(UUID uuid)  { return leader.equals(uuid); }

    public int size()                 { return members.size(); }

    public boolean hasChatEnabled(UUID uuid) { return chatEnabled.contains(uuid); }
    public void toggleChat(UUID uuid) {
        if (!chatEnabled.remove(uuid)) chatEnabled.add(uuid);
    }

    /** Returns all member UUIDs except the given one. */
    public Set<UUID> getOthers(UUID uuid) {
        Set<UUID> others = new LinkedHashSet<>(members);
        others.remove(uuid);
        return others;
    }
}
