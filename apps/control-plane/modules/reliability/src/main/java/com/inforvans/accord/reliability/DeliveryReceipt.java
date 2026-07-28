package com.inforvans.accord.reliability;

public record DeliveryReceipt(String receiptId, String receiptDigest) {
    public DeliveryReceipt {
        receiptId = ReliabilityValues.receiptId(receiptId);
        receiptDigest = ReliabilityValues.digest(receiptDigest, "receiptDigest");
    }
}
