package com.skylane.station;

public enum DeviceType {
    ROBOT, DRONE;

    public String json() {
        return name().toLowerCase();
    }
}
