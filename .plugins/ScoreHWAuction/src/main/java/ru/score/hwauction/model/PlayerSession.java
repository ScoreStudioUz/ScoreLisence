package ru.score.hwauction.model;

import java.util.UUID;

public final class PlayerSession {

    private final UUID playerUuid;
    private String categoryFilter = "all";
    private SortType sortType = SortType.NEWEST;
    private AuctionMode viewMode = AuctionMode.MARKET;
    private boolean soundEnabled = true;
    private int currentPage = 0;
    private String searchQuery = null;
    private String playerFilter = null;

    public PlayerSession(UUID playerUuid) {
        this.playerUuid = playerUuid;
    }

    public UUID getPlayerUuid() { return playerUuid; }

    public String getCategoryFilter() { return categoryFilter; }
    public void setCategoryFilter(String categoryFilter) {
        this.categoryFilter = categoryFilter;
        this.currentPage = 0; // Reset page when filter changes
    }

    public SortType getSortType() { return sortType; }
    public void setSortType(SortType sortType) {
        this.sortType = sortType;
        this.currentPage = 0;
    }

    public AuctionMode getViewMode() { return viewMode; }
    public void setViewMode(AuctionMode viewMode) {
        this.viewMode = viewMode;
        this.currentPage = 0;
    }

    public boolean isSoundEnabled() { return soundEnabled; }
    public void setSoundEnabled(boolean soundEnabled) { this.soundEnabled = soundEnabled; }

    public int getCurrentPage() { return currentPage; }
    public void setCurrentPage(int currentPage) { this.currentPage = Math.max(0, currentPage); }

    public String getSearchQuery() { return searchQuery; }
    public void setSearchQuery(String searchQuery) {
        this.searchQuery = (searchQuery != null && searchQuery.isBlank()) ? null : searchQuery;
        this.currentPage = 0;
    }

    public String getPlayerFilter() { return playerFilter; }
    public void setPlayerFilter(String playerFilter) {
        this.playerFilter = (playerFilter != null && playerFilter.isBlank()) ? null : playerFilter;
        this.currentPage = 0;
    }

    public boolean hasSearch() { return searchQuery != null; }
    public boolean hasPlayerFilter() { return playerFilter != null; }

    public void resetFilters() {
        this.categoryFilter = "all";
        this.searchQuery = null;
        this.playerFilter = null;
        this.currentPage = 0;
    }
}
