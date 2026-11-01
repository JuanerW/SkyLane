package com.skylane.station;

import java.time.Instant;
import java.util.UUID;

/** availableAt = when the device is next free (null = free now). Active devices only. */
public record Device(String code, UUID stationId, DeviceType type, Instant availableAt) {
}
