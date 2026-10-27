package com.skylane.station;

import com.skylane.geo.LatLng;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
@Profile("!memory")
public class JdbcStationRepository implements StationRepository {
    private final JdbcTemplate jdbc;

    public JdbcStationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Station> findActiveStations() {
        return jdbc.query("""
                SELECT id, code, name, address_text,
                       ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lng
                FROM station WHERE is_active ORDER BY code""",
                (rs, i) -> new Station(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getString("address_text"), new LatLng(rs.getDouble("lat"), rs.getDouble("lng"))));
    }

    /** availableAt = end of the device's last unreleased block (design.md: departure = max(now, last block end)). */
    @Override
    public List<Device> findActiveDevices(Station station, DeviceType type) {
        return jdbc.query("""
                SELECT d.code,
                       (SELECT max(b.ends_at) FROM device_block b
                         WHERE b.device_id = d.id AND b.released_at IS NULL) AS free_at
                FROM device d
                WHERE d.station_id = ? AND d.device_type = ? AND d.status = 'active'
                ORDER BY d.code""",
                (rs, i) -> {
                    Timestamp t = rs.getTimestamp("free_at");
                    return new Device(rs.getString("code"), station.id(), type, t == null ? null : t.toInstant());
                },
                station.id(), type.json());
    }
}
