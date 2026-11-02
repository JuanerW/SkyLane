package com.skylane.order;

import com.skylane.common.ApiException;
import com.skylane.order.OrderDtos.CancelRequest;
import com.skylane.order.OrderDtos.CreateOrderRequest;
import com.skylane.order.OrderDtos.CreateOrderResponse;
import com.skylane.order.OrderDtos.OrderDetail;
import com.skylane.order.OrderDtos.OrderSummary;
import com.skylane.order.OrderDtos.Review;
import com.skylane.order.OrderDtos.ReviewRequest;
import com.skylane.quote.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@Profile("!memory")
public class OrderController {
    private final OrderService orders;
    private final CurrentUserProvider currentUser;

    public OrderController(OrderService orders, CurrentUserProvider currentUser) {
        this.orders = orders;
        this.currentUser = currentUser;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateOrderResponse create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                      @Valid @RequestBody CreateOrderRequest req) {
        return orders.create(currentUser.currentUserId(), key, req);
    }

    @GetMapping
    public List<OrderSummary> list(@RequestParam(defaultValue = "100") int limit) {
        return orders.list(currentUser.currentUserId(), limit);
    }

    @GetMapping("/{id}")
    public OrderDetail get(@PathVariable String id) {
        return orders.get(currentUser.currentUserId(), orderId(id));
    }

    @PostMapping("/{id}/cancel")
    public Map<String, String> cancel(@PathVariable String id, @Valid @RequestBody(required = false) CancelRequest req) {
        return orders.cancel(currentUser.currentUserId(), orderId(id), req == null ? null : req.reason());
    }

    @PostMapping("/{id}/review")
    @ResponseStatus(HttpStatus.CREATED)
    public Review review(@PathVariable String id, @Valid @RequestBody ReviewRequest req) {
        return orders.review(currentUser.currentUserId(), orderId(id), req.rating(), req.comment());
    }

    static UUID orderId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found");
        }
    }
}
