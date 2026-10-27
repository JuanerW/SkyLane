package com.skylane.station;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/stations")
public class StationController {
    private final StationRepository stations;

    public StationController(StationRepository stations) {
        this.stations = stations;
    }

    public record StationDto(String id, String code, String name, double lat, double lng) {
    }

    @GetMapping
    public List<StationDto> list() {
        return stations.findActiveStations().stream()
                .map(s -> new StationDto(s.id().toString(), s.code(), s.name(), s.location().lat(), s.location().lng()))
                .toList();
    }
}
