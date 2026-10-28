package com.skylane.station;

import java.util.List;

/** Seeded in memory for now; swap for the JPA implementation once the Flyway schema is wired in. */
public interface StationRepository {
    List<Station> findActiveStations();

    List<Device> findActiveDevices(Station station, DeviceType type);
}
