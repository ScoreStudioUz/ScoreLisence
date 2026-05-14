package ru.score.hwauction.api.event;

import org.bukkit.event.Event;
import ru.score.hwauction.model.AuctionItem;

public abstract class AuctionEvent extends Event {

    private final AuctionItem item;

    protected AuctionEvent(AuctionItem item) {
        this.item = item;
    }

    public AuctionItem getAuctionItem() { return item; }
}
