package com.rrpe.inventoryservice.exception;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(String sku) {
        super("No product found for SKU '" + sku + "'");
    }
}
