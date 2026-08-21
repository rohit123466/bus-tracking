package com.dtc.bus_tracker.dto;

public class StopDto {
    private Long id;
    private String stopId;
    private String name;
    private Double latitude;
    private Double longitude;
    private Integer sequenceNumber;
    private Boolean wheelchairBoarding;

    // Manual getters
    public Long getId() { return id; }
    public String getStopId() { return stopId; }
    public String getName() { return name; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public Integer getSequenceNumber() { return sequenceNumber; }
    public Boolean getWheelchairBoarding() { return wheelchairBoarding; }

    // Manual setters
    public void setId(Long id) { this.id = id; }
    public void setStopId(String stopId) { this.stopId = stopId; }
    public void setName(String name) { this.name = name; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public void setSequenceNumber(Integer sequenceNumber) { this.sequenceNumber = sequenceNumber; }
    public void setWheelchairBoarding(Boolean wheelchairBoarding) { this.wheelchairBoarding = wheelchairBoarding; }
}