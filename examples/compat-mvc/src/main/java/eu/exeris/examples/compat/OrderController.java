/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.compat;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// tag::controller[]
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final Map<Long, Order> orders = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    @GetMapping("/{id}")
    public Order find(@PathVariable("id") long id) {
        Order order = orders.get(id);
        if (order == null) {
            throw new OrderNotFoundException(id);
        }
        return order;
    }

    @PostMapping
    public ResponseEntity<Order> create(@Valid @RequestBody CreateOrder request) {
        Order order = new Order(ids.incrementAndGet(), request.product(), request.quantity());
        orders.put(order.id(), order);
        return ResponseEntity.created(URI.create("/orders/" + order.id())).body(order);
    }
}
// end::controller[]
