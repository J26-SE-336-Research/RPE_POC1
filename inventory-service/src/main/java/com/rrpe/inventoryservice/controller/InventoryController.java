package com.rrpe.inventoryservice.controller;

import com.rrpe.inventoryservice.dto.ProductResponse;
import com.rrpe.inventoryservice.dto.StockReservationRequest;
import com.rrpe.inventoryservice.dto.StockReservationResponse;
import com.rrpe.inventoryservice.service.InventoryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/products")
    public List<ProductResponse> listProducts() {
        return inventoryService.listProducts();
    }

    @GetMapping("/products/{sku}")
    public ProductResponse getProduct(@PathVariable String sku) {
        return inventoryService.getProduct(sku);
    }

    @PostMapping("/reserve")
    public ResponseEntity<StockReservationResponse> reserve(@Valid @RequestBody StockReservationRequest request) {
        return ResponseEntity.ok(inventoryService.reserve(request));
    }

    @PostMapping("/release")
    public ResponseEntity<StockReservationResponse> release(@Valid @RequestBody StockReservationRequest request) {
        return ResponseEntity.ok(inventoryService.release(request));
    }

    @PostMapping("/confirm")
    public ResponseEntity<StockReservationResponse> confirm(@Valid @RequestBody StockReservationRequest request) {
        return ResponseEntity.ok(inventoryService.confirm(request));
    }
}
