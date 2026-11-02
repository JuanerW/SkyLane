package com.skylane.order;

import com.skylane.geo.Geo;
import com.skylane.geo.LatLng;

import java.util.List;

/**
 * Timetable of one delivery, in simulated seconds after departs_at (design.md, device simulator):
 * dispatched at 0 -> picked_up on reaching the pickup -> in_transit after a 60 s stop -> arrived at the drop-off
 * -> completed 30 s later. Drones add 60 s takeoff and 60 s landing (D18). Real time = simulated / sim speed.
 * Positions are computed from this, never stored (D15).
 */
public final class DeliveryPlan {
    public static final double ROBOT_MPS = 1.5;
    public static final double DRONE_MPS = 15;
    static final double PICKUP_STOP_S = 60;
    static final double COMPLETE_AFTER_S = 30;
    static final double DRONE_TAKEOFF_S = 60;
    static final double DRONE_LANDING_S = 60;

    public record Position(double lat, double lng, double progress, double remainingS) {
    }

    private final List<LatLng> points;
    private final double[] cumulative;
    private final double leg1M;
    private final double totalM;
    private final double speed;
    private final double takeoffS;
    private final double landingS;

    public DeliveryPlan(List<LatLng> points, LatLng pickup, String deviceType) {
        this.points = points;
        this.cumulative = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            cumulative[i] = cumulative[i - 1] + Geo.haversineM(points.get(i - 1), points.get(i));
        }
        this.totalM = cumulative[points.size() - 1];
        // The route passes through the pickup: split it at the vertex closest to it.
        int pickupIdx = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            double d = Geo.haversineM(points.get(i), pickup);
            if (d < best) {
                best = d;
                pickupIdx = i;
            }
        }
        this.leg1M = cumulative[pickupIdx];
        boolean drone = "drone".equals(deviceType);
        this.speed = drone ? DRONE_MPS : ROBOT_MPS;
        this.takeoffS = drone ? DRONE_TAKEOFF_S : 0;
        this.landingS = drone ? DRONE_LANDING_S : 0;
    }

    public double pickedUpAt() {
        return takeoffS + leg1M / speed;
    }

    public double inTransitAt() {
        return pickedUpAt() + PICKUP_STOP_S;
    }

    public double arrivedAt() {
        return inTransitAt() + (totalM - leg1M) / speed + landingS;
    }

    public double completedAt() {
        return arrivedAt() + COMPLETE_AFTER_S;
    }

    public double totalM() {
        return totalM;
    }

    /** Offset (simulated seconds) at which the order enters the given status. */
    public double offsetOf(String status) {
        return switch (status) {
            case "dispatched" -> 0;
            case "picked_up" -> pickedUpAt();
            case "in_transit" -> inTransitAt();
            case "arrived" -> arrivedAt();
            case "completed" -> completedAt();
            default -> throw new IllegalArgumentException(status);
        };
    }

    /** Status the order should be in, t simulated seconds after departure (t >= 0). */
    public String statusAt(double t) {
        if (t >= completedAt()) return "completed";
        if (t >= arrivedAt()) return "arrived";
        if (t >= inTransitAt()) return "in_transit";
        if (t >= pickedUpAt()) return "picked_up";
        return "dispatched";
    }

    /** Where the device is t simulated seconds after departure; remainingS is in simulated seconds. */
    public Position positionAt(double t) {
        double travelled;
        if (t < takeoffS) {
            travelled = 0;
        } else if (t < pickedUpAt()) {
            travelled = (t - takeoffS) * speed;
        } else if (t < inTransitAt()) {
            travelled = leg1M;
        } else {
            travelled = Math.min(totalM, leg1M + (t - inTransitAt()) * speed);
        }
        LatLng p = pointAt(travelled);
        double progress = totalM == 0 ? 1 : travelled / totalM;
        return new Position(p.lat(), p.lng(), progress, Math.max(0, arrivedAt() - t));
    }

    private LatLng pointAt(double distance) {
        for (int i = 1; i < points.size(); i++) {
            if (distance <= cumulative[i]) {
                double seg = cumulative[i] - cumulative[i - 1];
                double f = seg == 0 ? 0 : (distance - cumulative[i - 1]) / seg;
                LatLng a = points.get(i - 1);
                LatLng b = points.get(i);
                return new LatLng(a.lat() + (b.lat() - a.lat()) * f, a.lng() + (b.lng() - a.lng()) * f);
            }
        }
        return points.get(points.size() - 1);
    }
}
