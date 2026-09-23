package com.rrpe.inventoryservice.service;

import com.rrpe.inventoryservice.entity.Product;
import com.rrpe.inventoryservice.repository.ProductRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

// Seeds a handful of products on first startup so the service is
// immediately testable without a manual setup step. Only runs when
// the table is empty, so it is safe across container restarts.
@Component
public class DataSeeder implements CommandLineRunner {

    private final ProductRepository productRepository;

    public DataSeeder(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public void run(String... args) {
        if (productRepository.count() > 0) {
            return;
        }
        productRepository.save(new Product("SKU-KEYBOARD-01", "Mechanical Keyboard", new BigDecimal("59.99"), 50));
        productRepository.save(new Product("SKU-MOUSE-01", "Wireless Mouse", new BigDecimal("24.50"), 100));
        productRepository.save(new Product("SKU-MONITOR-01", "27-inch Monitor", new BigDecimal("189.00"), 15));
        productRepository.save(new Product("SKU-HEADSET-01", "Noise-Cancelling Headset", new BigDecimal("79.99"), 3));
    }
}
