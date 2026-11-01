package com.skylane.routing;

import com.skylane.geo.LatLng;

import java.util.List;

/** A route station -> pickup -> dropoff. leg1 = station to pickup, leg2 = pickup to dropoff. */
public record RoutePlan(List<LatLng> points, double leg1M, double leg2M) {
    public double totalM() {
        return leg1M + leg2M;
    }
}
