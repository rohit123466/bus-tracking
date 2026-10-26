package com.dtc.bus_tracker.live;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * DTC's published route list, fare stages and special-service timetables, joined to the live
 * feed: a route's detail lists the buses running on it right now.
 */
@RestController
@RequestMapping("/api/transit/catalog")
public class ServiceCatalogController {
  /** A route with everything published about it plus its live buses. */
  public record RouteDetail(
      ServiceCatalog.MasterRoute route,
      List<String> gtfsRouteIds,
      List<ServiceCatalog.FareStageRoute> fareStages,
      List<ServiceCatalog.SpecialService> services,
      List<LiveTransitService.Bus> liveBuses,
      boolean liveFeedAvailable) {}

  private final ServiceCatalog catalog;
  private final TransitIndex index;
  private final LiveTransitService transit;

  public ServiceCatalogController(ServiceCatalog catalog, TransitIndex index, LiveTransitService transit) {
    this.catalog = catalog;
    this.index = index;
    this.transit = transit;
  }

  @GetMapping
  public ServiceCatalog.Summary summary() {
    return catalog.summary();
  }

  /** Routes matching a number or terminal name; {@code category} narrows by service category. */
  @GetMapping("/routes")
  public List<ServiceCatalog.MasterRoute> routes(
      @RequestParam(defaultValue = "") String query,
      @RequestParam(defaultValue = "") String category,
      @RequestParam(defaultValue = "50") int limit) {
    if (query.length() > 80 || category.length() > 80)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query is too long");
    return catalog.routes(query, category, Math.min(limit, 500));
  }

  /** By route number (102A, DS-1) or master route id (MR_0040). */
  @GetMapping("/routes/{route}")
  public RouteDetail route(@PathVariable String route) {
    var r = find(route);
    var services = catalog.services(r);
    Set<String> ids = new LinkedHashSet<>();
    if (r.upRouteId() != null) ids.add(r.upRouteId());
    if (r.downRouteId() != null) ids.add(r.downRouteId());
    services.forEach(s -> ids.addAll(s.gtfsRouteIds()));
    // GTFS variants of the same number (116AUP2) are listed under one master route.
    index.routes.values().stream()
        .filter(g -> g.number() != null && ServiceCatalog.key(g.number()).equals(r.key()))
        .forEach(g -> ids.add(g.id()));
    var live = transit.buses("").buses().stream().filter(b -> ids.contains(b.routeId())).toList();
    return new RouteDetail(r, List.copyOf(ids), catalog.fareStages(r), services, live, transit.feedAvailable());
  }

  @GetMapping("/routes/{route}/fare-stages")
  public List<ServiceCatalog.FareStageRoute> fareStages(
      @PathVariable String route, @RequestParam(defaultValue = "") String direction) {
    String d = direction.trim().toUpperCase(Locale.ROOT);
    return catalog.fareStages(find(route)).stream()
        .filter(f -> d.isEmpty() || f.direction().equalsIgnoreCase(d)
            || (d.equals("DOWN") && f.direction().equalsIgnoreCase("DN")))
        .toList();
  }

  /** Night, NCR, Express or Destination services; all of them without {@code kind}. */
  @GetMapping("/services")
  public List<ServiceCatalog.SpecialService> services(@RequestParam(defaultValue = "") String kind) {
    if (kind.isBlank()) return catalog.services((ServiceCatalog.Kind) null);
    try {
      return catalog.services(ServiceCatalog.Kind.valueOf(kind.trim().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "kind must be one of " + Arrays.toString(ServiceCatalog.Kind.values()));
    }
  }

  @GetMapping("/services/{id}")
  public ServiceCatalog.SpecialService service(@PathVariable String id) {
    return catalog.service(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown service " + id));
  }

  private ServiceCatalog.MasterRoute find(String route) {
    return catalog.route(route)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown route " + route));
  }
}
