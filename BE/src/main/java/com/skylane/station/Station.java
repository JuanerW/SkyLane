package com.skylane.station;

import com.skylane.geo.LatLng;

import java.util.UUID;

public record Station(UUID id, String code, String name, String addressText, LatLng location) {
}
