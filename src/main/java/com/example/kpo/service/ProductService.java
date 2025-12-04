package com.example.kpo.service;

import com.example.kpo.entity.Product;
import com.example.kpo.entity.Warehouse;
import com.example.kpo.repository.ProductRepository;
import com.example.kpo.repository.WarehouseRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;

    public ProductService(ProductRepository productRepository,
                          WarehouseRepository warehouseRepository) {
        this.productRepository = productRepository;
        this.warehouseRepository = warehouseRepository;
    }

    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    public Optional<Product> getProductById(Long id) {
        return productRepository.findById(id);
    }

    public Product createProduct(Product product) {
        product.setWarehouse(resolveWarehouse(product));
        return productRepository.save(product);
    }

    public Optional<Product> updateProduct(Long id, Product product) {
        return productRepository.findById(id)
                .map(existing -> {
                    existing.setName(product.getName());
                    existing.setCount(product.getCount());
                    existing.setWarehouse(resolveWarehouse(product));
                    return productRepository.save(existing);
                });
    }

    public void deleteProduct(Long id) {
        productRepository.deleteById(id);
    }

    private Warehouse resolveWarehouse(Product product) {
        if (product.getWarehouse() == null || product.getWarehouse().getId() == null) {
            throw new IllegalArgumentException("Warehouse id is required");
        }
        return warehouseRepository.findById(product.getWarehouse().getId())
                .orElseThrow(() -> new EntityNotFoundException("Warehouse not found"));
    }
}
