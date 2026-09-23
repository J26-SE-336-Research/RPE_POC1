package com.rrpe.inventoryservice.service;

import com.rrpe.inventoryservice.dto.ProductResponse;
import com.rrpe.inventoryservice.dto.StockReservationRequest;
import com.rrpe.inventoryservice.dto.StockReservationResponse;
import com.rrpe.inventoryservice.entity.Product;
import com.rrpe.inventoryservice.exception.InsufficientStockException;
import com.rrpe.inventoryservice.exception.ProductNotFoundException;
import com.rrpe.inventoryservice.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class InventoryService {

    private final ProductRepository productRepository;

    public InventoryService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public List<ProductResponse> listProducts() {
        return productRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    public ProductResponse getProduct(String sku) {
        Product product = productRepository.findBySku(sku)
                .orElseThrow(() -> new ProductNotFoundException(sku));
        return toResponse(product);
    }

    // Reserves stock for an order that has not yet been paid for.
    // The pessimistic lock on the row means two orders racing for the
    // last unit of the same SKU cannot both succeed.
    @Transactional
    public StockReservationResponse reserve(StockReservationRequest request) {
        Product product = productRepository.findBySkuForUpdate(request.sku())
                .orElseThrow(() -> new ProductNotFoundException(request.sku()));

        if (product.getAvailableQuantity() < request.quantity()) {
            throw new InsufficientStockException(
                    request.sku(), request.quantity(), product.getAvailableQuantity());
        }

        product.setReservedQuantity(product.getReservedQuantity() + request.quantity());
        productRepository.save(product);

        return new StockReservationResponse(
                product.getSku(), request.quantity(), product.getAvailableQuantity());
    }

    // Compensating action for a saga-style rollback: if payment fails
    // after stock was reserved, the reservation must be released so
    // the units become available to other orders again.
    @Transactional
    public StockReservationResponse release(StockReservationRequest request) {
        Product product = productRepository.findBySkuForUpdate(request.sku())
                .orElseThrow(() -> new ProductNotFoundException(request.sku()));

        int newReserved = Math.max(0, product.getReservedQuantity() - request.quantity());
        product.setReservedQuantity(newReserved);
        productRepository.save(product);

        return new StockReservationResponse(
                product.getSku(), request.quantity(), product.getAvailableQuantity());
    }

    // Called once a payment has actually succeeded: converts a
    // reservation into a permanent deduction from physical stock.
    @Transactional
    public StockReservationResponse confirm(StockReservationRequest request) {
        Product product = productRepository.findBySkuForUpdate(request.sku())
                .orElseThrow(() -> new ProductNotFoundException(request.sku()));

        int reserved = Math.max(0, product.getReservedQuantity() - request.quantity());
        int stock = Math.max(0, product.getStockQuantity() - request.quantity());
        product.setReservedQuantity(reserved);
        product.setStockQuantity(stock);
        productRepository.save(product);

        return new StockReservationResponse(
                product.getSku(), request.quantity(), product.getAvailableQuantity());
    }

    private ProductResponse toResponse(Product p) {
        return new ProductResponse(
                p.getId(), p.getSku(), p.getName(), p.getPrice(),
                p.getStockQuantity(), p.getReservedQuantity(), p.getAvailableQuantity());
    }
}
