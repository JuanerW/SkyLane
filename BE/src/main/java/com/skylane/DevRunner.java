package com.skylane;

import com.skylane.entity.Device;
import com.skylane.entity.DeviceStatus;
import com.skylane.entity.DeviceType;
import com.skylane.entity.Station;
import com.skylane.repository.DeviceRepository;
import com.skylane.repository.StationRepository;
import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DevRunner.class);
    private static final GeometryFactory GEOMETRY_FACTORY =
            new GeometryFactory(new PrecisionModel(), 4326);

    private final StationRepository stationRepository;
    private final DeviceRepository deviceRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int stationsCreated = 0;
        int devicesCreated = 0;

        for (StationSeed seed : stationSeeds()) {
            Station station = stationRepository.findByCode(seed.code()).orElse(null);
            if (station == null) {
                station = new Station();
                station.setCode(seed.code());
                station.setName(seed.name());
                station.setAddressText(seed.address());
                station.setLocation(point(seed.longitude(), seed.latitude()));
                station.setActive(true);
                station = stationRepository.save(station);
                stationsCreated++;
            }

            for (DeviceSeed deviceSeed : seed.devices()) {
                if (deviceRepository.findByCode(deviceSeed.code()).isPresent()) {
                    continue;
                }

                Device device = new Device();
                device.setCode(deviceSeed.code());
                device.setStation(station);
                device.setDeviceType(deviceSeed.type());
                device.setStatus(DeviceStatus.ACTIVE);
                device.setLocation(point(seed.longitude(), seed.latitude()));
                deviceRepository.save(device);
                devicesCreated++;
            }
        }

        log.info("Development seed data ready: {} new stations, {} new devices",
                stationsCreated, devicesCreated);
    }

    private static List<StationSeed> stationSeeds() {
        return List.of(
                new StationSeed("soma", "SoMa Station", "San Francisco, CA (SoMa)",
                        37.7785, -122.3950, devices("R-10", "DRN-10")),
                new StationSeed("mission", "Mission Station", "San Francisco, CA (Mission District)",
                        37.7599, -122.4148, devices("R-20", "DRN-20")),
                new StationSeed("marina", "Marina Station", "San Francisco, CA (Marina District)",
                        37.8008, -122.4368, devices("R-30", "DRN-30"))
        );
    }

    private static List<DeviceSeed> devices(String robotPrefix, String dronePrefix) {
        return List.of(
                new DeviceSeed(robotPrefix + "1", DeviceType.ROBOT),
                new DeviceSeed(robotPrefix + "2", DeviceType.ROBOT),
                new DeviceSeed(robotPrefix + "3", DeviceType.ROBOT),
                new DeviceSeed(dronePrefix + "1", DeviceType.DRONE),
                new DeviceSeed(dronePrefix + "2", DeviceType.DRONE)
        );
    }

    private static org.locationtech.jts.geom.Point point(double longitude, double latitude) {
        return GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude));
    }

    private record StationSeed(String code, String name, String address,
                               double latitude, double longitude, List<DeviceSeed> devices) {}

    private record DeviceSeed(String code, DeviceType type) {}
}
