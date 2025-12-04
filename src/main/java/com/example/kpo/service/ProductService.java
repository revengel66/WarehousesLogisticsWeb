package com.example.kpo.service;

import com.example.kpo.entity.Category;
import com.example.kpo.entity.Product;
import com.example.kpo.repository.CategoryRepository;
import com.example.kpo.repository.ProductRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    public ProductService(ProductRepository productRepository,
                          CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
    }

    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    public Optional<Product> getProductById(Long id) {
        return productRepository.findById(id);
    }

    public Product createProduct(Product product) {
        product.setCategory(resolveCategory(product.getCategory()));
        return productRepository.save(product);
    }

    public Optional<Product> updateProduct(Long id, Product product) {
        return productRepository.findById(id)
                .map(existing -> {
                    existing.setName(product.getName());
                    existing.setInfo(product.getInfo());
                    existing.setCategory(resolveCategory(product.getCategory()));
                    return productRepository.save(existing);
                });
    }

    public void deleteProduct(Long id) {
        productRepository.deleteById(id);
    }

    private Category resolveCategory(Category category) {
        if (category == null || category.getId() == null) {
            throw new IllegalArgumentException("Category id is required");
        }
        return categoryRepository.findById(category.getId())
                .orElseThrow(() -> new EntityNotFoundException("Category not found"));
    }
}
