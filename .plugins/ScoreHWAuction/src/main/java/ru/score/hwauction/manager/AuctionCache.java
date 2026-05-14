package ru.score.hwauction.manager;

import ru.score.hwauction.model.AuctionItem;
import ru.score.hwauction.model.AuctionStatus;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class AuctionCache {

    private final ConcurrentHashMap<Integer, AuctionItem> items = new ConcurrentHashMap<>();

    public void put(AuctionItem item) {
        items.put(item.getId(), item);
    }

    public Optional<AuctionItem> get(int id) {
        return Optional.ofNullable(items.get(id));
    }

    public void remove(int id) {
        items.remove(id);
    }

    public void clear() {
        items.clear();
    }

    public void putAll(Collection<AuctionItem> list) {
        list.forEach(this::put);
    }

    public Collection<AuctionItem> all() {
        return Collections.unmodifiableCollection(items.values());
    }

    public List<AuctionItem> active() {
        return items.values().stream()
                .filter(AuctionItem::isActive)
                .collect(Collectors.toList());
    }

    public List<AuctionItem> expiredAndActive() {
        return items.values().stream()
                .filter(i -> i.getStatus() == AuctionStatus.ACTIVE && i.isExpired())
                .collect(Collectors.toList());
    }

    public int size() { return items.size(); }

    public boolean contains(int id) { return items.containsKey(id); }
}
