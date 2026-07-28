package com.inforvans.accord.reliability;

public record HandlerReceipt(String receiptId, String receiptDigest) {
    public HandlerReceipt {
        receiptId = ReliabilityValues.receiptId(receiptId);
        receiptDigest = ReliabilityValues.digest(receiptDigest, "receiptDigest");
    }
}
