package com.dtc.bus_tracker.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
@Entity
@Table(name = "stops")
@Builder  // ← ADD THIS
@NoArgsConstructor
@AllArgsConstructor
public class Stop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stop_id", unique = true, nullable = false)
    private String stopId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Column(name = "sequence_number")
    private Integer sequenceNumber;

    /** GTFS stops.txt wheelchair_boarding == "1". Null when the feed omits the column. */
    @Column(name = "wheelchair_boarding")
    private Boolean wheelchairBoarding;

    @Builder.Default
    @ManyToMany
    @JoinTable(
            name = "route_stop",
            joinColumns = @JoinColumn(name = "stop_id"),
            inverseJoinColumns = @JoinColumn(name = "route_id")
    )
    private List<Route> routes = new ArrayList<>();

    // Getters
    public Long getId() { return id; }
    public String getStopId() { return stopId; }
    public String getName() { return name; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public Integer getSequenceNumber() { return sequenceNumber; }
    public Boolean getWheelchairBoarding() { return wheelchairBoarding; }
    public List<Route> getRoutes() { return routes; }

    // Setters
    public void setId(Long id) { this.id = id; }
    public void setStopId(String stopId) { this.stopId = stopId; }
    public void setName(String name) { this.name = name; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public void setSequenceNumber(Integer sequenceNumber) { this.sequenceNumber = sequenceNumber; }
    public void setWheelchairBoarding(Boolean wheelchairBoarding) { this.wheelchairBoarding = wheelchairBoarding; }
    public void setRoutes(List<Route> routes) { this.routes = routes; }
}