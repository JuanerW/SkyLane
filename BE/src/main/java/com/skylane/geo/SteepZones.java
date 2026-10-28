package com.skylane.geo;

import java.util.List;

/**
 * Hand-drawn rectangles around the steep areas robots cannot serve (design.md: Twin Peaks, Nob Hill,
 * Russian Hill, Telegraph Hill). Approximate on purpose; replace with real elevation data later.
 */
public final class SteepZones {
    // Tightened around the hills themselves so flat roads at their feet (Bay St, Columbus Ave,
    // Embarcadero) are not flagged. Still hand-drawn: check against real elevation data before launch.
    public static final List<List<LatLng>> ZONES = List.of(
            rect(37.7600, -122.4560, 37.7440, -122.4350),   // Twin Peaks
            rect(37.7950, -122.4180, 37.7885, -122.4095),   // Nob Hill (Pacific Ave to Sutter, Larkin to Powell)
            rect(37.8020, -122.4235, 37.7975, -122.4130),   // Russian Hill (south of Lombard St)
            rect(37.8065, -122.4090, 37.7995, -122.4020));  // Telegraph Hill (Coit Tower)

    private static List<LatLng> rect(double north, double west, double south, double east) {
        return List.of(new LatLng(north, west), new LatLng(north, east), new LatLng(south, east), new LatLng(south, west));
    }

    public static boolean touchedBy(List<LatLng> path) {
        for (List<LatLng> zone : ZONES) {
            if (path.size() == 1 && Geo.pointInPolygon(path.get(0), zone)) {
                return true;
            }
            for (int i = 0; i + 1 < path.size(); i++) {
                if (Geo.segmentTouchesPolygon(path.get(i), path.get(i + 1), zone)) {
                    return true;
                }
            }
        }
        return false;
    }

    private SteepZones() {
    }
}
