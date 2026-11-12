package com.skylane.ops;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Design 8.1 request and response bodies. JSON is snake_case (global Jackson setting). */
public final class OpsDtos {
    private OpsDtos() {
    }

    /** D31: idle / on_order / returning, or the device status when it is not active (maintenance / offline). */
    public record LiveDevice(String deviceId, String code, String deviceType, String stationId, String state,
                             double lat, double lng, String orderId) {
    }

    public record StationCount(String stationId, String name, int idle, int onOrder, int returning, int inactive) {
    }

    public record Overview(List<StationCount> stations, int ordersToday, int activeOrders, int scheduledOrders) {
    }

    public record DeviceRow(String deviceId, String code, String deviceType, String status, String state,
                            String stationId, String stationName, String operatorId, double lat, double lng,
                            String orderId, int futureBookings) {
    }

    public record CreateDevice(@NotBlank @Size(max = 40) String code, @NotBlank String stationId,
                               @NotBlank @Pattern(regexp = "robot|drone") String deviceType, String operatorId) {
    }

    public record PatchDevice(@Pattern(regexp = "active|maintenance|offline") String status, String stationId) {
    }

    public record Booking(String kind, String orderId, Instant startsAt, Instant endsAt, String orderStatus) {
    }

    public record OpsOrder(String orderId, String status, String deviceCode, String deviceType, String stationName,
                           String recipientName, String recipientPhoneLast4, String pickupText, String dropoffText,
                           int priceCents, Instant createdAt, Instant departsAt, Instant etaAt, Instant completedAt,
                           boolean late) {
    }

    public record Metrics(Instant from, Instant to, int completed, Double onTimeRate, Double avgDeliveryMinutes,
                          Map<Integer, Integer> ratings, Double utilization) {
    }

    public record CreateOperator(@NotBlank @Size(max = 100) String name) {
    }

    public record OperatorRef(String id, String name) {
    }

    public record CreateUser(@NotBlank @Email String email, @NotBlank @Size(max = 100) String displayName,
                             @NotBlank @Size(min = 8, max = 100) String password, @NotBlank String operatorId) {
    }

    public record PatchUser(Boolean disabled, Boolean forceLogout) {
    }

    public record UserRef(String id, String email, String displayName, String role, String operatorId,
                          Instant disabledAt) {
    }
}
