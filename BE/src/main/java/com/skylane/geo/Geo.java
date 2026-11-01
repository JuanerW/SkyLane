package com.skylane.geo;

import java.util.List;

public final class Geo {
    private static final double EARTH_RADIUS_M = 6_371_000d;

    public static double haversineM(LatLng a, LatLng b) {
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(h));
    }

    public static boolean pointInPolygon(LatLng p, List<LatLng> poly) {
        boolean inside = false;
        for (int i = 0, j = poly.size() - 1; i < poly.size(); j = i++) {
            LatLng a = poly.get(i);
            LatLng b = poly.get(j);
            if (pointOnSegment(p, a, b)) {
                return true;
            }
            boolean crosses = (a.lat() > p.lat()) != (b.lat() > p.lat())
                    && p.lng() < (b.lng() - a.lng()) * (p.lat() - a.lat()) / (b.lat() - a.lat()) + a.lng();
            if (crosses) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** True if the segment a-b touches the polygon (an endpoint inside, or it crosses an edge). */
    public static boolean segmentTouchesPolygon(LatLng a, LatLng b, List<LatLng> poly) {
        if (pointInPolygon(a, poly) || pointInPolygon(b, poly)) {
            return true;
        }
        for (int i = 0, j = poly.size() - 1; i < poly.size(); j = i++) {
            if (segmentsIntersect(a, b, poly.get(i), poly.get(j))) {
                return true;
            }
        }
        return false;
    }

    private static boolean segmentsIntersect(LatLng p1, LatLng p2, LatLng p3, LatLng p4) {
        double d1 = cross(p3, p4, p1);
        double d2 = cross(p3, p4, p2);
        double d3 = cross(p1, p2, p3);
        double d4 = cross(p1, p2, p4);
        if (pointOnSegment(p1, p3, p4) || pointOnSegment(p2, p3, p4)
                || pointOnSegment(p3, p1, p2) || pointOnSegment(p4, p1, p2)) {
            return true;
        }
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    /** Include edge overlap and vertex contact, allowing for floating-point coordinate error. */
    private static boolean pointOnSegment(LatLng p, LatLng a, LatLng b) {
        double coordinateEpsilon = 1e-10;
        return Math.abs(cross(a, b, p)) <= 1e-12
                && p.lat() >= Math.min(a.lat(), b.lat()) - coordinateEpsilon
                && p.lat() <= Math.max(a.lat(), b.lat()) + coordinateEpsilon
                && p.lng() >= Math.min(a.lng(), b.lng()) - coordinateEpsilon
                && p.lng() <= Math.max(a.lng(), b.lng()) + coordinateEpsilon;
    }

    private static double cross(LatLng a, LatLng b, LatLng c) {
        return (b.lng() - a.lng()) * (c.lat() - a.lat()) - (b.lat() - a.lat()) * (c.lng() - a.lng());
    }

    private Geo() {
    }
}
