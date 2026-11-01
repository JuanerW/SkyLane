package com.skylane.routing;

import com.skylane.geo.LatLng;

public interface RoutingClient {
    /** Walking-network route for robots. Implementations must never throw; they fall back to a straight line. */
    RoutePlan walkingRoute(LatLng station, LatLng pickup, LatLng dropoff);
}
