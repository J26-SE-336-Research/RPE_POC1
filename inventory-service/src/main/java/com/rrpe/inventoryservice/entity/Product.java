package com.rrpe.inventoryservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private BigDecimal price;

    // Total units physically in stock.
    @Column(nullable = false)
    private Integer stockQuantity;

    // Units already promised to an order that has not yet been
    // confirmed or released. Kept separate from stockQuantity so a
    // failed payment can release a reservation without a second
    // service having to know how the reservation was represented.
    @Column(nullable = false)
    private Integer reservedQuantity = 0;

    public Product(String sku, String name, BigDecimal price, Integer stockQuantity) {
        this.sku = sku;
        this.name = name;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.reservedQuantity = 0;
    }

    public int getAvailableQuantity() {
        return stockQuantity - reservedQuantity;
    }
}
