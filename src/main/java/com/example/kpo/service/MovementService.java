package com.example.kpo.service;

import com.example.kpo.entity.Counterparty;
import com.example.kpo.entity.Employee;
import com.example.kpo.entity.Movement;
import com.example.kpo.entity.MovementProduct;
import com.example.kpo.entity.MovementType;
import com.example.kpo.entity.Product;
import com.example.kpo.entity.Warehouse;
import com.example.kpo.entity.WarehouseProduct;
import com.example.kpo.repository.CounterpartyRepository;
import com.example.kpo.repository.EmployeeRepository;
import com.example.kpo.repository.MovementRepository;
import com.example.kpo.repository.ProductRepository;
import com.example.kpo.repository.WarehouseProductRepository;
import com.example.kpo.repository.WarehouseRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class MovementService {

    private final MovementRepository movementRepository;
    private final ProductRepository productRepository;
    private final EmployeeRepository employeeRepository;
    private final CounterpartyRepository counterpartyRepository;
    private final WarehouseRepository warehouseRepository;
    private final WarehouseProductRepository warehouseProductRepository;

    public MovementService(MovementRepository movementRepository,
                           ProductRepository productRepository,
                           EmployeeRepository employeeRepository,
                           CounterpartyRepository counterpartyRepository,
                           WarehouseRepository warehouseRepository,
                           WarehouseProductRepository warehouseProductRepository) {
        this.movementRepository = movementRepository;
        this.productRepository = productRepository;
        this.employeeRepository = employeeRepository;
        this.counterpartyRepository = counterpartyRepository;
        this.warehouseRepository = warehouseRepository;
        this.warehouseProductRepository = warehouseProductRepository;
    }

    public List<Movement> getAllMovements() {
        return movementRepository.findAll();
    }

    public List<Movement> getMovementsByType(MovementType type) {
        return movementRepository.findByType(type);
    }

    public Optional<Movement> getMovementById(Long id) {
        return movementRepository.findById(id);
    }

    @Transactional
    public Movement createMovement(Movement movement) {
        Movement prepared = new Movement();
        copyAndResolveRelations(movement, prepared);
        validateRelations(prepared);
        applyMovement(prepared);
        return movementRepository.save(prepared);
    }

    @Transactional
    public Optional<Movement> updateMovement(Long id, Movement movement) {
        return movementRepository.findById(id)
                .map(existing -> {
                    Movement previousState = cloneMovement(existing);
                    copyAndResolveRelations(movement, existing);
                    validateRelations(existing);
                    if (!applyMovementDelta(previousState, existing)) {
                        revertMovement(previousState);
                        applyMovement(existing);
                    }
                    return movementRepository.save(existing);
                });
    }

    @Transactional
    public void deleteMovement(Long id) {
        movementRepository.findById(id).ifPresent(movement -> {
            revertMovement(movement);
            movementRepository.delete(movement);
        });
    }

    private void copyAndResolveRelations(Movement source, Movement target) {
        target.setDate(source.getDate());
        target.setType(source.getType());
        target.setInfo(source.getInfo());
        target.setEmployee(resolveRequiredEmployee(source.getEmployee(), "employee"));
        target.setCounterparty(resolveOptionalCounterparty(source.getCounterparty()));
        target.setWarehouse(resolveRequiredWarehouse(source.getWarehouse(), "warehouse"));
        target.setTargetEmployee(resolveOptionalEmployee(source.getTargetEmployee(), "targetEmployee"));
        target.setTargetWarehouse(resolveOptionalWarehouse(source.getTargetWarehouse(), "targetWarehouse"));
        target.getItems().clear();
        List<MovementProduct> incomingItems = source.getItems();
        if (incomingItems != null && !incomingItems.isEmpty()) {
            List<MovementProduct> resolvedItems = new ArrayList<>();
            for (MovementProduct item : incomingItems) {
                if (item.getQuantity() == null || item.getQuantity() <= 0) {
                    throw new IllegalArgumentException("Movement item quantity must be greater than 0");
                }
                MovementProduct resolved = new MovementProduct();
                resolved.setMovement(target);
                resolved.setProduct(resolveProduct(item.getProduct()));
                resolved.setQuantity(item.getQuantity());
                resolvedItems.add(resolved);
            }
            target.getItems().addAll(resolvedItems);
        }
    }

    private Product resolveProduct(Product product) {
        if (product == null || product.getId() == null) {
            throw new IllegalArgumentException("Product id is required");
        }
        return productRepository.findById(product.getId())
                .orElseThrow(() -> new EntityNotFoundException("Product not found"));
    }

    private Employee resolveRequiredEmployee(Employee employee, String field) {
        if (employee == null || employee.getId() == null) {
            throw new IllegalArgumentException("Employee id is required for " + field);
        }
        return employeeRepository.findById(employee.getId())
                .orElseThrow(() -> new EntityNotFoundException("Employee not found"));
    }

    private Employee resolveOptionalEmployee(Employee employee, String field) {
        if (employee == null) {
            return null;
        }
        if (employee.getId() == null) {
            throw new IllegalArgumentException("Employee id is required for " + field);
        }
        return employeeRepository.findById(employee.getId())
                .orElseThrow(() -> new EntityNotFoundException("Employee not found"));
    }

    private Counterparty resolveOptionalCounterparty(Counterparty counterparty) {
        if (counterparty == null) {
            return null;
        }
        if (counterparty.getId() == null) {
            throw new IllegalArgumentException("Counterparty id is required");
        }
        return counterpartyRepository.findById(counterparty.getId())
                .orElseThrow(() -> new EntityNotFoundException("Counterparty not found"));
    }

    private Warehouse resolveRequiredWarehouse(Warehouse warehouse, String field) {
        if (warehouse == null || warehouse.getId() == null) {
            throw new IllegalArgumentException("Warehouse id is required for " + field);
        }
        return warehouseRepository.findById(warehouse.getId())
                .orElseThrow(() -> new EntityNotFoundException("Warehouse not found"));
    }

    private Warehouse resolveOptionalWarehouse(Warehouse warehouse, String field) {
        if (warehouse == null) {
            return null;
        }
        if (warehouse.getId() == null) {
            throw new IllegalArgumentException("Warehouse id is required for " + field);
        }
        return warehouseRepository.findById(warehouse.getId())
                .orElseThrow(() -> new EntityNotFoundException("Warehouse not found"));
    }

    private void validateRelations(Movement movement) {
        MovementType type = movement.getType();
        if (movement.getDate() == null) {
            throw new IllegalArgumentException("Movement date is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("Movement type is required");
        }
        switch (type) {
            case INBOUND -> validateInbound(movement);
            case OUTBOUND -> validateOutbound(movement);
            case TRANSFER -> validateTransfer(movement);
            default -> throw new IllegalArgumentException("Unsupported movement type");
        }
    }

    private void validateInbound(Movement movement) {
        if (movement.getCounterparty() == null) {
            throw new IllegalArgumentException("Counterparty is required for inbound movement");
        }
        if (movement.getTargetEmployee() != null || movement.getTargetWarehouse() != null) {
            throw new IllegalArgumentException("Target employee/warehouse must be empty for inbound movement");
        }
    }

    private void validateOutbound(Movement movement) {
        if (movement.getCounterparty() == null) {
            throw new IllegalArgumentException("Counterparty is required for outbound movement");
        }
        if (movement.getTargetEmployee() != null || movement.getTargetWarehouse() != null) {
            throw new IllegalArgumentException("Target employee/warehouse must be empty for outbound movement");
        }
    }

    private void validateTransfer(Movement movement) {
        if (movement.getCounterparty() != null) {
            throw new IllegalArgumentException("Counterparty must be empty for transfer movement");
        }
        if (movement.getTargetEmployee() == null) {
            throw new IllegalArgumentException("Target employee is required for transfer movement");
        }
        if (movement.getTargetWarehouse() == null) {
            throw new IllegalArgumentException("Target warehouse is required for transfer movement");
        }
    }

    private void applyMovement(Movement movement) {
        switch (movement.getType()) {
            case INBOUND -> movement.getItems()
                    .forEach(item -> increaseStock(movement.getWarehouse(), item.getProduct(), item.getQuantity()));
            case OUTBOUND -> movement.getItems()
                    .forEach(item -> decreaseStock(movement.getWarehouse(), item.getProduct(), item.getQuantity()));
            case TRANSFER -> movement.getItems()
                    .forEach(item -> {
                        decreaseStock(movement.getWarehouse(), item.getProduct(), item.getQuantity());
                        increaseStock(movement.getTargetWarehouse(), item.getProduct(), item.getQuantity());
                    });
            default -> throw new IllegalArgumentException("Unsupported movement type");
        }
    }

    private void revertMovement(Movement movement) {
        switch (movement.getType()) {
            case INBOUND -> movement.getItems()
                    .forEach(item -> decreaseStock(movement.getWarehouse(), item.getProduct(), item.getQuantity()));
            case OUTBOUND -> movement.getItems()
                    .forEach(item -> increaseStock(movement.getWarehouse(), item.getProduct(), item.getQuantity()));
            case TRANSFER -> movement.getItems()
                    .forEach(item -> {
                        decreaseStock(movement.getTargetWarehouse(), item.getProduct(), item.getQuantity());
                        increaseStock(movement.getWarehouse(), item.getProduct(), item.getQuantity());
                    });
            default -> throw new IllegalArgumentException("Unsupported movement type");
        }
    }

    private void increaseStock(Warehouse warehouse, Product product, int quantity) {
        WarehouseProduct stock = warehouseProductRepository.findByWarehouseAndProduct(warehouse, product)
                .orElseGet(() -> new WarehouseProduct(warehouse, product, 0));
        stock.setQuantity(stock.getQuantity() + quantity);
        warehouseProductRepository.save(stock);
    }

    private void decreaseStock(Warehouse warehouse, Product product, int quantity) {
        WarehouseProduct stock = warehouseProductRepository.findByWarehouseAndProduct(warehouse, product)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product " + product.getId() + " is not available on warehouse " + warehouse.getId()));
        if (stock.getQuantity() < quantity) {
            throw new IllegalArgumentException("Not enough product " + product.getId()
                    + " on warehouse " + warehouse.getId());
        }
        int nextQuantity = stock.getQuantity() - quantity;
        if (nextQuantity <= 0) {
            warehouseProductRepository.delete(stock);
        } else {
            stock.setQuantity(nextQuantity);
            warehouseProductRepository.save(stock);
        }
    }

    private Movement cloneMovement(Movement source) {
        Movement clone = new Movement();
        clone.setType(source.getType());
        clone.setWarehouse(source.getWarehouse());
        clone.setTargetWarehouse(source.getTargetWarehouse());
        clone.setEmployee(source.getEmployee());
        clone.setTargetEmployee(source.getTargetEmployee());
        clone.setCounterparty(source.getCounterparty());
        List<MovementProduct> snapshotItems = new ArrayList<>();
        if (source.getItems() != null) {
            for (MovementProduct item : source.getItems()) {
                MovementProduct clonedItem = new MovementProduct();
                clonedItem.setMovement(clone);
                clonedItem.setProduct(item.getProduct());
                clonedItem.setQuantity(item.getQuantity());
                snapshotItems.add(clonedItem);
            }
        }
        clone.setItems(snapshotItems);
        return clone;
    }

    private boolean applyMovementDelta(Movement previous, Movement current) {
        if (previous.getType() != current.getType()) {
            return false;
        }
        return switch (current.getType()) {
            case INBOUND -> applyInboundDelta(previous, current);
            case OUTBOUND -> applyOutboundDelta(previous, current);
            case TRANSFER -> applyTransferDelta(previous, current);
        };
    }

    private boolean applyInboundDelta(Movement previous, Movement current) {
        if (!sameWarehouse(previous.getWarehouse(), current.getWarehouse())) {
            return false;
        }
        Map<Long, ProductQuantity> previousItems = aggregateItems(previous.getItems());
        Map<Long, ProductQuantity> currentItems = aggregateItems(current.getItems());
        Set<Long> productIds = new HashSet<>();
        productIds.addAll(previousItems.keySet());
        productIds.addAll(currentItems.keySet());
        for (Long productId : productIds) {
            int prevQuantity = previousItems.containsKey(productId) ? previousItems.get(productId).quantity : 0;
            int currQuantity = currentItems.containsKey(productId) ? currentItems.get(productId).quantity : 0;
            int delta = currQuantity - prevQuantity;
            if (delta == 0) {
                continue;
            }
            Product product = currentItems.containsKey(productId)
                    ? currentItems.get(productId).product
                    : previousItems.get(productId).product;
            if (delta > 0) {
                increaseStock(current.getWarehouse(), product, delta);
            } else {
                decreaseStock(current.getWarehouse(), product, -delta);
            }
        }
        return true;
    }

    private boolean applyOutboundDelta(Movement previous, Movement current) {
        if (!sameWarehouse(previous.getWarehouse(), current.getWarehouse())) {
            return false;
        }
        Map<Long, ProductQuantity> previousItems = aggregateItems(previous.getItems());
        Map<Long, ProductQuantity> currentItems = aggregateItems(current.getItems());
        Set<Long> productIds = new HashSet<>();
        productIds.addAll(previousItems.keySet());
        productIds.addAll(currentItems.keySet());
        for (Long productId : productIds) {
            int prevQuantity = previousItems.containsKey(productId) ? previousItems.get(productId).quantity : 0;
            int currQuantity = currentItems.containsKey(productId) ? currentItems.get(productId).quantity : 0;
            int delta = prevQuantity - currQuantity;
            if (delta == 0) {
                continue;
            }
            Product product = currentItems.containsKey(productId)
                    ? currentItems.get(productId).product
                    : previousItems.get(productId).product;
            if (delta > 0) {
                increaseStock(current.getWarehouse(), product, delta);
            } else {
                decreaseStock(current.getWarehouse(), product, -delta);
            }
        }
        return true;
    }

    private boolean applyTransferDelta(Movement previous, Movement current) {
        if (!sameWarehouse(previous.getWarehouse(), current.getWarehouse())
                || !sameWarehouse(previous.getTargetWarehouse(), current.getTargetWarehouse())) {
            return false;
        }
        Map<Long, ProductQuantity> previousItems = aggregateItems(previous.getItems());
        Map<Long, ProductQuantity> currentItems = aggregateItems(current.getItems());
        Set<Long> productIds = new HashSet<>();
        productIds.addAll(previousItems.keySet());
        productIds.addAll(currentItems.keySet());
        for (Long productId : productIds) {
            int prevQuantity = previousItems.containsKey(productId) ? previousItems.get(productId).quantity : 0;
            int currQuantity = currentItems.containsKey(productId) ? currentItems.get(productId).quantity : 0;
            int delta = currQuantity - prevQuantity;
            if (delta == 0) {
                continue;
            }
            Product product = currentItems.containsKey(productId)
                    ? currentItems.get(productId).product
                    : previousItems.get(productId).product;
            if (delta > 0) {
                decreaseStock(current.getWarehouse(), product, delta);
                increaseStock(current.getTargetWarehouse(), product, delta);
            } else {
                int amount = -delta;
                increaseStock(current.getWarehouse(), product, amount);
                decreaseStock(current.getTargetWarehouse(), product, amount);
            }
        }
        return true;
    }

    private Map<Long, ProductQuantity> aggregateItems(List<MovementProduct> items) {
        Map<Long, ProductQuantity> result = new HashMap<>();
        if (items == null) {
            return result;
        }
        for (MovementProduct item : items) {
            if (item.getProduct() == null || item.getProduct().getId() == null) {
                continue;
            }
            result.compute(item.getProduct().getId(), (id, state) -> {
                if (state == null) {
                    return new ProductQuantity(item.getProduct(), item.getQuantity());
                }
                state.quantity += item.getQuantity();
                return state;
            });
        }
        return result;
    }

    private boolean sameWarehouse(Warehouse left, Warehouse right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return Objects.equals(left.getId(), right.getId());
    }

    private static class ProductQuantity {
        private final Product product;
        private int quantity;

        private ProductQuantity(Product product, int quantity) {
            this.product = product;
            this.quantity = quantity;
        }
    }
}
