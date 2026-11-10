package com.skylane.ops;

import com.skylane.auth.JwtAuthFilter;
import com.skylane.common.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Returns the devices visible to the logged-in operator or administrator.
 * Each device includes its current state and number of future bookings.
 */
@RestController
@Profile("!memory")
@RequestMapping("/api/v1/ops/devices")
public class OpsDeviceController {

    private final JdbcTemplate jdbc;

    public OpsDeviceController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Device information returned to the frontend.
    public record DeviceDto(
            UUID device_id,
            String code,
            String device_type,
            UUID station_id,
            String state,
            long future_bookings
    ) {}

    // Use the device status and current booking to determine its state.
    // Count future bookings that have not been released.
    private static final String DEVICE_QUERY = """
            SELECT d.id, d.code, d.device_type, d.station_id,
                   CASE
                       WHEN d.status <> 'active' THEN d.status
                       WHEN b.kind = 'order' THEN 'on_order'
                       WHEN b.kind = 'return' THEN 'returning'
                       ELSE COALESCE(b.kind, 'idle')
                   END AS state,
                   (
                       SELECT COUNT(*)
                       FROM device_block upcoming
                       WHERE upcoming.device_id = d.id
                         AND upcoming.released_at IS NULL
                         AND upcoming.starts_at > CURRENT_TIMESTAMP
                   ) AS future_bookings
            FROM device d
            LEFT JOIN device_block b
                   ON b.device_id = d.id
                  AND b.released_at IS NULL
                  AND b.starts_at <= CURRENT_TIMESTAMP
                  AND CURRENT_TIMESTAMP < b.ends_at
            """;

    @GetMapping
    public List<DeviceDto> listDevices() {
        JwtAuthFilter.AccessScope scope =
                JwtAuthFilter.currentAccessScopeOrNull();

        if (scope == null) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Login required"
            );
        }

        // Administrators can view all devices.
        if ("admin".equals(scope.role())) {
            return jdbc.query(
                    DEVICE_QUERY + " ORDER BY d.code",
                    this::readDevice
            );
        }

        // Operators can only view devices owned by their company.
        if ("operator".equals(scope.role()) && scope.operatorId() != null) {
            return jdbc.query(
                    DEVICE_QUERY + " WHERE d.operator_id = ? ORDER BY d.code",
                    this::readDevice,
                    scope.operatorId()
            );
        }

        throw new ApiException(
                HttpStatus.FORBIDDEN, "FORBIDDEN", "Operator access required"
        );
    }

    // Convert one database row into a DeviceDto.
    private DeviceDto readDevice(ResultSet rs, int rowNum) throws SQLException {
        return new DeviceDto(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("device_type"),
                rs.getObject("station_id", UUID.class),
                rs.getString("state"),
                rs.getLong("future_bookings")
        );
    }
}