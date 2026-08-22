package com.dtc.bus_tracker.dto;

public class NearbyBusResponse {
    private String vehicleId;
    private String routeCode;
    private String routeName;
    private String stopName;
    private Double distanceToStop;
    private Double distanceToUser;
    private Integer etaMinutes;
    private Double busLatitude;
    private Double busLongitude;
    private Boolean wheelchairAccessible;
    /** Reserved wheelchair spaces on board when wheelchairAccessible is true, else 0. */
    private Integer wheelchairSpaces;
    private Boolean wheelchairSpaceAvailable;

    // Builder pattern
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private NearbyBusResponse response = new NearbyBusResponse();
        public Builder vehicleId(String vehicleId) { response.vehicleId = vehicleId; return this; }
        public Builder routeCode(String routeCode) { response.routeCode = routeCode; return this; }
        public Builder routeName(String routeName) { response.routeName = routeName; return this; }
        public Builder stopName(String stopName) { response.stopName = stopName; return this; }
        public Builder distanceToStop(Double distanceToStop) { response.distanceToStop = distanceToStop; return this; }
        public Builder distanceToUser(Double distanceToUser) { response.distanceToUser = distanceToUser; return this; }
        public Builder etaMinutes(Integer etaMinutes) { response.etaMinutes = etaMinutes; return this; }
        public Builder busLatitude(Double busLatitude) { response.busLatitude = busLatitude; return this; }
        public Builder busLongitude(Double busLongitude) { response.busLongitude = busLongitude; return this; }
        public Builder wheelchairAccessible(Boolean wheelchairAccessible) { response.wheelchairAccessible = wheelchairAccessible; return this; }
        public Builder wheelchairSpaces(Integer wheelchairSpaces) { response.wheelchairSpaces = wheelchairSpaces; return this; }
        public Builder wheelchairSpaceAvailable(Boolean wheelchairSpaceAvailable) { response.wheelchairSpaceAvailable = wheelchairSpaceAvailable; return this; }
        public NearbyBusResponse build() { return response; }
    }

    // Manual getters
    public String getVehicleId() { return vehicleId; }
    public String getRouteCode() { return routeCode; }
    public String getRouteName() { return routeName; }
    public String getStopName() { return stopName; }
    public Double getDistanceToStop() { return distanceToStop; }
    public Double getDistanceToUser() { return distanceToUser; }
    public Integer getEtaMinutes() { return etaMinutes; }
    public Double getBusLatitude() { return busLatitude; }
    public Double getBusLongitude() { return busLongitude; }
    public Boolean getWheelchairAccessible() { return wheelchairAccessible; }
    public Integer getWheelchairSpaces() { return wheelchairSpaces; }
    public Boolean getWheelchairSpaceAvailable() { return wheelchairSpaceAvailable; }
}