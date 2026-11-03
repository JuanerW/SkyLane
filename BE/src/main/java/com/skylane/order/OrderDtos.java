package com.skylane.order;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class OrderDtos {
    private OrderDtos() {
    }

    public record Recipient(@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 30) String phone) {
    }

    public record CreateOrderRequest(@NotNull String quoteId, @NotNull String optionId,
                                     @NotNull @Valid Recipient recipient) {
    }

    public record DeviceRef(String code, String deviceType) {
    }

    public record StationRef(String code, String name, double lat, double lng) {
    }

    public record Place(String text, double lat, double lng) {
    }

    public record Item(String description, boolean isFragile, BigDecimal weightKg) {
    }

    public record CreateOrderResponse(String orderId, String status, DeviceRef device, int priceCents,
                                      Instant departsAt, Instant etaAt) {
    }

    public record OrderSummary(String orderId, String status, String deviceType, int priceCents, Instant createdAt,
                               Instant departsAt, Instant etaAt, Instant completedAt, String pickupText,
                               String dropoffText, String recipientName, boolean reviewed) {
    }

    public record TimelineEntry(long eventId, String status, Instant occurredAt) {
    }

    public record Review(int rating, String comment, Instant createdAt) {
    }

    /** Flat on purpose: GET /orders/{id} extends the summary row (FE OrderDetail extends OrderSummary). */
    public record OrderDetail(String orderId, String status, String deviceType, int priceCents, Instant createdAt,
                              Instant departsAt, Instant etaAt, Instant completedAt, String pickupText,
                              String dropoffText, String recipientName, boolean reviewed,
                              DeviceRef device, StationRef station, Item item, String recipientPhone, String notes,
                              Place pickup, Place dropoff, int distanceM, List<double[]> routePoints,
                              boolean cancellable, Instant canceledAt, String cancelReason,
                              List<TimelineEntry> timeline, Review review) {
    }

    public record CancelRequest(@Size(max = 200) String reason) {
    }

    public record ReviewRequest(@JsonDeserialize(using = ReviewRatingDeserializer.class)
                                @NotNull @Min(1) @Max(5) Integer rating, @Size(max = 1000) String comment) {
    }
}
