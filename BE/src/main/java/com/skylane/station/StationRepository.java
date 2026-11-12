package com.skylane.station;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Seeded in memory for now; swap for the JPA implementation once the Flyway schema is wired in. */
public interface StationRepository {
    List<Station> findActiveStations();

    List<Device> findActiveDevices(Station station, DeviceType type);

    /** D42: earliest start at or after {@code from} where the device is free for {@code length}. */
    default Instant earliestStart(Device device, Instant from, Duration length) {
        return device.availableAt() == null || device.availableAt().isBefore(from) ? from : device.availableAt();
    }
}
