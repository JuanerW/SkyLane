package com.skylane.station;



import com.skylane.geo.LatLng;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** No-database stand-in (profile 'memory') seeded like BE/README.md. Use JdbcStationRepository otherwise. */
@Repository
@Profile("memory")
public class InMemoryStationRepository implements StationRepository {
    private final List<Station> stations = List.of(
            new Station(UUID.fromString("00000000-0000-0000-0000-000000000001"), "soma", "SoMa Station",
                    "SoMa, San Francisco", new LatLng(37.7785, -122.3950)),
            new Station(UUID.fromString("00000000-0000-0000-0000-000000000002"), "mission", "Mission Station",
                    "Mission District, San Francisco", new LatLng(37.7599, -122.4148)),
            new Station(UUID.fromString("00000000-0000-0000-0000-000000000003"), "marina", "Marina Station",
                    "Marina District, San Francisco", new LatLng(37.8008, -122.4368)));

    private final List<Device> devices = new ArrayList<>();

    public InMemoryStationRepository() {
        for (Station s : stations) {
            for (int i = 1; i <= 2; i++) {
                devices.add(new Device("RBT-" + s.code() + "-" + i, s.id(), DeviceType.ROBOT, null));
                devices.add(new Device("DRN-" + s.code() + "-" + i, s.id(), DeviceType.DRONE, null));
            }
        }
    }

    @Override
    public List<Station> findActiveStations() {
        return stations;
    }

    @Override
    public List<Device> findActiveDevices(Station station, DeviceType type) {
        return devices.stream().filter(d -> d.stationId().equals(station.id()) && d.type() == type).toList();
    }
}
