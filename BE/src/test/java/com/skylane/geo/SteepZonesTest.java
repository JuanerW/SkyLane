package com.skylane.geo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SteepZonesTest {
    @Test
    void flatWaterfrontRoadsAreNotSteep() {
        // Points sampled from the real walking route Marina -> Bay St -> Columbus Ave -> Embarcadero -> Ferry Building
        List<LatLng> bayStreet = List.of(new LatLng(37.800828, -122.436805), new LatLng(37.801459, -122.431893),
                new LatLng(37.802087, -122.426298), new LatLng(37.802865, -122.419906), new LatLng(37.803475, -122.414976),
                new LatLng(37.802113, -122.412985), new LatLng(37.800235, -122.41029), new LatLng(37.799574, -122.409329),
                new LatLng(37.798775, -122.408174), new LatLng(37.797553, -122.406411), new LatLng(37.796, -122.404162),
                new LatLng(37.795925, -122.399544), new LatLng(37.796469, -122.39567), new LatLng(37.795484, -122.393727));
        assertThat(SteepZones.touchedBy(bayStreet)).isFalse();
    }

    @Test
    void routesOverTheHillsAreSteep() {
        assertThat(SteepZones.touchedBy(List.of(new LatLng(37.7544, -122.4477)))).as("Twin Peaks").isTrue();
        assertThat(SteepZones.touchedBy(List.of(new LatLng(37.8024, -122.4058)))).as("Coit Tower").isTrue();
        // Straight line from Union Square to the Marina climbs over Nob Hill / Russian Hill
        assertThat(SteepZones.touchedBy(List.of(new LatLng(37.7880, -122.4075), new LatLng(37.8030, -122.4400)))).isTrue();
        // Line that crosses Nob Hill without any vertex inside it
        assertThat(SteepZones.touchedBy(List.of(new LatLng(37.7920, -122.4250), new LatLng(37.7920, -122.4000)))).isTrue();
    }
    @Test
    void boundaryEdgesAndVerticesAreSteep() {
        assertThat(SteepZones.touchedBy(List.of(new LatLng(37.7950, -122.417),
                new LatLng(37.7950, -122.411)))).as("overlap north edge").isTrue();
        assertThat(SteepZones.touchedBy(List.of(new LatLng(37.7900, -122.4095),
                new LatLng(37.7940, -122.4095)))).as("overlap east edge").isTrue();
        assertThat(SteepZones.touchedBy(List.of(new LatLng(37.7950, -122.4095))))
                .as("single boundary vertex").isTrue();
        assertThat(SteepZones.touchedBy(List.of(new LatLng(37.7960, -122.4085),
                new LatLng(37.7950, -122.4095)))).as("endpoint touches vertex").isTrue();
    }

    @Test
    void pathsOutsideTheBoundaryRemainAllowed() {
        var zone = SteepZones.ZONES.get(1);
        assertThat(Geo.segmentTouchesPolygon(new LatLng(37.7950, -122.4250),
                new LatLng(37.7950, -122.4200), zone)).as("collinear but disjoint").isFalse();
        assertThat(Geo.segmentTouchesPolygon(new LatLng(37.795001, -122.417),
                new LatLng(37.795001, -122.411), zone)).as("parallel just outside").isFalse();
    }
}
