package ru.score.hwauction.model;

public enum BuyResult {
    SUCCESS,
    NOT_FOUND,
    ALREADY_SOLD,
    NOT_ENOUGH_MONEY,
    SELF_BUY,
    INVENTORY_FULL,
    BLACKLISTED,
    ERROR
}
