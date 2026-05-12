package ru.score.hwauction.model;

import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.UUID;

public final class AuctionItem {

    private int id;
    private UUID sellerUuid;
    private String sellerName;
    private ItemStack itemStack;
    private int amount;
    private double price;          // Total price
    private double pricePerUnit;   // Price per single unit
    private String category;
    private AuctionMode mode;
    private boolean fullOnly;      // Must buy entire stack

    // Bid/Auction fields (only used when mode == AUCTION)
    private double startPrice;
    private double currentBid;
    private double stepPrice;
    private UUID topBidderUuid;
    private String topBidderName;

    private long listedAt;
    private long expiresAt;
    private AuctionStatus status;

    // ---- Constructor for new listings ----
    public AuctionItem(UUID sellerUuid, String sellerName, ItemStack itemStack,
                       double price, String category, AuctionMode mode,
                       boolean fullOnly, long expiresAt) {
        Objects.requireNonNull(sellerUuid, "sellerUuid cannot be null");
        Objects.requireNonNull(sellerName, "sellerName cannot be null");
        Objects.requireNonNull(itemStack, "itemStack cannot be null");
        Objects.requireNonNull(category, "category cannot be null");
        Objects.requireNonNull(mode, "mode cannot be null");

        this.sellerUuid = sellerUuid;
        this.sellerName = sellerName;
        this.itemStack = itemStack.clone();
        this.amount = itemStack.getAmount();
        this.price = price;
        this.pricePerUnit = (this.amount > 0) ? price / this.amount : price;
        this.category = category;
        this.mode = mode;
        this.fullOnly = fullOnly;
        this.listedAt = System.currentTimeMillis();
        this.expiresAt = expiresAt;
        this.status = AuctionStatus.ACTIVE;

        if (mode == AuctionMode.AUCTION) {
            this.startPrice = price;
            this.currentBid = price;
        }
    }

    // ---- No-arg constructor for DB deserialization ----
    public AuctionItem() {}

    // ---- Computed helpers ----

    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }

    public boolean isActive() {
        return status == AuctionStatus.ACTIVE && !isExpired();
    }

    public boolean isSeller(UUID uuid) {
        return uuid != null && uuid.equals(sellerUuid);
    }

    public boolean hasBidder() {
        return topBidderUuid != null;
    }

    public long getRemainingMillis() {
        return Math.max(0, expiresAt - System.currentTimeMillis());
    }

    /** Next bid amount a player needs to pay */
    public double getNextBidAmount() {
        if (!hasBidder()) return startPrice;          // First bid = start price
        return currentBid + stepPrice;
    }

    // ---- Getters / Setters ----

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public UUID getSellerUuid() { return sellerUuid; }
    public void setSellerUuid(UUID sellerUuid) { this.sellerUuid = sellerUuid; }

    public String getSellerName() { return sellerName; }
    public void setSellerName(String sellerName) { this.sellerName = sellerName; }

    public ItemStack getItemStack() { return itemStack; }
    public void setItemStack(ItemStack itemStack) { this.itemStack = itemStack; }

    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }

    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = price; }

    public double getPricePerUnit() { return pricePerUnit; }
    public void setPricePerUnit(double pricePerUnit) { this.pricePerUnit = pricePerUnit; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public AuctionMode getMode() { return mode; }
    public void setMode(AuctionMode mode) { this.mode = mode; }

    public boolean isFullOnly() { return fullOnly; }
    public void setFullOnly(boolean fullOnly) { this.fullOnly = fullOnly; }

    public double getStartPrice() { return startPrice; }
    public void setStartPrice(double startPrice) { this.startPrice = startPrice; }

    public double getCurrentBid() { return currentBid; }
    public void setCurrentBid(double currentBid) { this.currentBid = currentBid; }

    public double getStepPrice() { return stepPrice; }
    public void setStepPrice(double stepPrice) { this.stepPrice = stepPrice; }

    public UUID getTopBidderUuid() { return topBidderUuid; }
    public void setTopBidderUuid(UUID topBidderUuid) { this.topBidderUuid = topBidderUuid; }

    public String getTopBidderName() { return topBidderName; }
    public void setTopBidderName(String topBidderName) { this.topBidderName = topBidderName; }

    public long getListedAt() { return listedAt; }
    public void setListedAt(long listedAt) { this.listedAt = listedAt; }

    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }

    public AuctionStatus getStatus() { return status; }
    public void setStatus(AuctionStatus status) { this.status = status; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AuctionItem other)) return false;
        return id == other.id;
    }

    @Override
    public int hashCode() { return Integer.hashCode(id); }

    @Override
    public String toString() {
        return "AuctionItem{id=" + id + ", seller=" + sellerName + ", mode=" + mode + ", status=" + status + "}";
    }
}
