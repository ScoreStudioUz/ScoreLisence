package ru.score.hwauction.model;

public enum BidResult {
    SUCCESS,
    NOT_FOUND,
    ALREADY_SOLD,
    NOT_ENOUGH_MONEY,
    SELF_BID,
    ALREADY_TOP_BIDDER,
    WRONG_MODE,
    EXPIRED,
    ERROR
}
