package com.cartbridge.api.repository;

import com.cartbridge.api.entity.Product;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public class ProductRepository {
    private final List<Product> products = List.of(
            new Product(
                    "everyday-backpack",
                    "Everyday Backpack",
                    "A durable 20L recycled-fabric backpack for commuting and weekend trips.",
                    new BigDecimal("68.00"),
                    "USD"
            ),
            new Product(
                    "wireless-earbuds",
                    "Wireless Earbuds",
                    "Comfort-fit Bluetooth earbuds with a compact charging case.",
                    new BigDecimal("89.99"),
                    "USD"
            ),
            new Product(
                    "ceramic-coffee-set",
                    "Ceramic Coffee Set",
                    "A handmade stoneware pour-over dripper and matching mug.",
                    new BigDecimal("42.50"),
                    "USD"
            )
    );

    public List<Product> findAll() {
        return products;
    }

    public Optional<Product> findById(String productId) {
        return products.stream().filter(product -> product.id().equals(productId)).findFirst();
    }
}
