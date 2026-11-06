package com.dtc.bus_tracker.live;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Search bus stand & live arrivals". A bus stand is the set of GTFS stops sharing one name
 * (compared after {@link #normalize}) within {@link #STAND_MERGE_METERS} of each other: usually one
 * stop per side of the road, or the bays of a terminal.
 *
 * <p>Live arrivals reuse {@link RegionService#upcoming}, the same engine as the Live Map and Nearby
 * (TripUpdates when present, else the bus's GPS position on its route's learned path or timetable
 * stop path, at its current speed), for every stop of the stand, across all of Delhi. Nothing here
 * invents a position or a time: a bus is listed only when the realtime feed reports it, and
 * timetable times are returned separately, labelled {@link #SOURCE_SCHEDULED}.
 */
@Service
public class StandService {
  /** Same-name stops this close (chained) are one stand. */
  static final double STAND_MERGE_METERS = 300;
  static final int DEFAULT_LIMIT = 10;
  static final int MAX_LIMIT = 30;
  static final int MIN_QUERY_CHARS = 2;

  /** ARRIVING: the bus is this close to the stop... */
  static final double ARRIVING_METERS = 300;
  /** ...or expected within this many minutes. */
  static final double ARRIVING_MINUTES = 2;
  /** APPROACHING: expected within this many minutes; ON_THE_WAY beyond it. */
  static final double APPROACHING_MINUTES = 10;
  /** DELAYED: the bus's GPS fix is older than this (the feed drops it at 120 s)... */
  static final long GPS_LAGGING_SECONDS = 90;
  /** ...or its timetable trip is known and it is predicted this many minutes late or more. */
  static final double BEHIND_SCHEDULE_MINUTES = 5;
  /** A reported speed below this counts as stopped (noted in the status reason). */
  static final double STOPPED_KMH = 3;

  /** Timetable departures are looked up this far ahead. */
  static final int SCHEDULE_WINDOW_MINUTES = 60;
  static final int SCHEDULED_PER_ROUTE = 2;
  static final int MAX_SCHEDULED = 30;
  /** Answers are reused for one feed snapshot, at most this long (GPS ages keep moving). */
  static final long CACHE_MS = 5000;
  /** Bus positions are described by the nearest stop within this distance. */
  static final double LOCATION_LABEL_METERS = 500;

  static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Kolkata");
  private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

  public static final String SOURCE_LIVE = "LIVE_GPS";
  public static final String SOURCE_SCHEDULED = "SCHEDULED";
  public static final String DISTANCE_ALONG_ROUTE = "ALONG_ROUTE";
  public static final String DISTANCE_STRAIGHT_LINE = "STRAIGHT_LINE";
  public static final String SCHEDULED_NOTICE =
      "Live location unavailable – Showing scheduled arrival information";

  public enum Status { ARRIVING, APPROACHING, ON_THE_WAY, DELAYED }

  /** LIVE: tracked buses listed; NO_LIVE_BUSES: feed fine, none heading here; FEED_UNAVAILABLE. */
  public enum LiveState { LIVE, NO_LIVE_BUSES, FEED_UNAVAILABLE }

  /**
   * A search suggestion. {@code id} is the stand's main stop; any member ID opens the stand.
   * {@code hint} ("near …", the closest differently named stop) is set only when another
   * suggestion has the same name, so the two can be told apart.
   */
  public record Stand(
      String id,
      String name,
      double lat,
      double lng,
      List<String> stopIds,
      List<String> routeNumbers,
      int routeCount,
      String hint) {}

  public record StandStop(String id, String name, double lat, double lng) {}

  /**
   * A bus the realtime feed reports heading to the stand. Field names match the Live Map's
   * upcoming-bus entries ({@code etaMinutes}, {@code availability}, {@code lastGpsUpdate}, ...).
   */
  public record BusArrival(
      /** The stand's stop the bus is heading to. */
      String stopId,
      String stopName,
      String routeNumber,
      /** Published destination of the bus's GTFS route (direction-specific); null when unknown. */
      String destination,
      /**
       * The route's timetable pattern ends at this stand: the bus finishes its trip here and
       * cannot be boarded onwards, so it is left out of the summary counts.
       */
      boolean terminatesHere,
      String vehicleId,
      Double busLatitude,
      Double busLongitude,
      /** "Near <stop name>" for the bus's GPS position; null when no stop is close. */
      String currentLocation,
      Double distanceMeters,
      Double distanceKm,
      /** {@link #DISTANCE_ALONG_ROUTE} or {@link #DISTANCE_STRAIGHT_LINE}. */
      String distanceBasis,
      Double etaMinutes,
      /** How the ETA was produced (TRIP_UPDATE, LEARNED_ROUTE_ESTIMATE, ...); see RegionService. */
      String availability,
      String etaLabel,
      String reason,
      /** One of {@link Status}. */
      String status,
      String statusReason,
      /** Minutes behind (+) or ahead (-) of the timetable; only when the feed's trip is known. */
      Double scheduleDeviationMinutes,
      Double speedKmh,
      Integer remainingStops,
      long lastGpsUpdate,
      long gpsAgeSeconds,
      /** Always {@link #SOURCE_LIVE}. */
      String dataSource,
      String routeCheck,
      List<String> likelyRoutes) {}

  /** A timetable departure; never a live position. */
  public record ScheduledArrival(
      String stopId,
      String stopName,
      String routeNumber,
      String destination,
      String tripId,
      /** Local time (Asia/Kolkata), HH:mm. */
      String scheduledTime,
      long scheduledEpochSeconds,
      double minutesUntil,
      /** Always {@link #SOURCE_SCHEDULED}. */
      String dataSource) {}

  public record Summary(
      int liveBusCount,
      /** Live buses ARRIVING or APPROACHING (within {@value #APPROACHING_MINUTES} min). */
      int approachingCount,
      BusArrival nextArrival,
      ScheduledArrival nextScheduled) {}

  public record StandArrivals(
      String standId,
      String stopName,
      double lat,
      double lng,
      List<StandStop> stops,
      List<String> routeNumbers,
      /** Live buses, soonest first. */
      List<BusArrival> arrivals,
      /** Timetable departures in the next hour for routes with no live bus listed. */
      List<ScheduledArrival> scheduled,
      Summary summary,
      /** One of {@link LiveState}. */
      String liveState,
      /** {@link #SCHEDULED_NOTICE} when no live bus is listed but the timetable has departures. */
      String notice,
      boolean feedAvailable,
      long feedFetchedAt,
      long generatedAt,
      List<String> warnings) {}

  /** One stand in the search index, with its precomputed match keys. */
  record Cluster(
      String id,
      String name,
      String key,
      String[] tokens,
      String compact,
      List<TransitIndex.Stop> stops,
      double lat,
      double lng,
      List<String> routeNumbers) {
    Stand view(String hint) {
      return new Stand(
          id, name, lat, lng, stops.stream().map(TransitIndex.Stop::id).toList(), routeNumbers,
          routeNumbers.size(), hint);
    }
  }

  record StandIndex(List<Cluster> stands, Map<String, Cluster> byStop) {}

  private final TransitIndex index;
  private final LiveTransitService transit;
  private final RealtimeSnapshot live;
  private final RegionService regions;

  private volatile StandIndex standIndex;
  private volatile Map<String, List<TransitIndex.Trip>> tripsByRoute;

  private record Cached(long generation, long builtAtMs, StandArrivals value) {}

  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  public StandService(
      TransitIndex index, LiveTransitService transit, RealtimeSnapshot live, RegionService regions) {
    this.index = index;
    this.transit = transit;
    this.live = live;
    this.regions = regions;
  }

  // ── Name matching ────────────────────────────────────────────────────────

  /** Spellings used interchangeably in DTC stop names, mapped to one form. */
  static final Map<String, String> SYNONYMS =
      Map.of(
          "sector", "sec",
          "sectors", "sec",
          "kashmiri", "kashmere",
          "kashmir", "kashmere",
          "stn", "station",
          "rly", "railway",
          "mkt", "market",
          "rd", "road");

  private static final Pattern LETTER_DIGIT = Pattern.compile("(?<=\\p{L})(?=\\p{Nd})|(?<=\\p{Nd})(?=\\p{L})");
  private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{Nd}]+");

  /**
   * Case-, punctuation- and spacing-insensitive form of a stop name: "Rohini Sec21" and
   * "ROHINI SECTOR 21" both become "rohini sec 21".
   */
  static String normalize(String raw) {
    if (raw == null) return "";
    String s = raw.toLowerCase(Locale.ROOT).replace("&", " and ");
    s = LETTER_DIGIT.matcher(s).replaceAll(" ");
    s = NON_WORD.matcher(s).replaceAll(" ").trim();
    if (s.isEmpty()) return "";
    StringJoiner out = new StringJoiner(" ");
    for (String t : s.split(" ")) out.add(SYNONYMS.getOrDefault(t, t));
    return out.toString();
  }

  /**
   * Query tokens, normalised like names; a partly typed word that can only be the start of a
   * synonym ("sect", "kashmi") is replaced by its common form, so suggestions don't vanish
   * mid-word.
   */
  static String[] queryTokens(String query) {
    String q = normalize(query);
    if (q.isEmpty()) return new String[0];
    String[] tokens = q.split(" ");
    for (int i = 0; i < tokens.length; i++) {
      String t = tokens[i];
      if (t.length() < 3) continue;
      for (var e : SYNONYMS.entrySet())
        if (e.getKey().startsWith(t) && !e.getValue().startsWith(t)) {
          tokens[i] = e.getValue();
          break;
        }
    }
    return tokens;
  }

  /**
   * Lower is better; -1 = no match. 0 exact name, 1 name starts with the query, 2 every query word
   * starts a word of the name and the first words agree, 3 every query word starts a word of the
   * name, 4 the query appears inside the name with spaces ignored.
   */
  static int rank(Cluster c, String[] q) {
    if (q.length == 0) return -1;
    String joined = String.join(" ", q);
    if (c.key().equals(joined)) return 0;
    if (c.key().startsWith(joined)) return 1;
    boolean all = true;
    for (String t : q) {
      boolean found = false;
      for (String n : c.tokens())
        if (n.startsWith(t)) {
          found = true;
          break;
        }
      if (!found) {
        all = false;
        break;
      }
    }
    if (all) return c.tokens()[0].startsWith(q[0]) ? 2 : 3;
    String compact = joined.replace(" ", "");
    return compact.length() >= 3 && c.compact().contains(compact) ? 4 : -1;
  }

  /** Route numbers in natural order: 34, 100, 166, 166A, 990, D-12. */
  static final Comparator<String> ROUTE_ORDER =
      Comparator.comparingInt(StandService::leadingNumber).thenComparing(Comparator.naturalOrder());

  private static int leadingNumber(String route) {
    int i = 0;
    while (i < route.length() && i < 9 && Character.isDigit(route.charAt(i))) i++;
    return i == 0 ? Integer.MAX_VALUE : Integer.parseInt(route.substring(0, i));
  }

  // ── Stand index ──────────────────────────────────────────────────────────

  StandIndex standIndex() {
    var built = standIndex;
    if (built == null) {
      synchronized (this) {
        if (standIndex == null) standIndex = buildIndex();
        built = standIndex;
      }
    }
    return built;
  }

  /**
   * Grouping key: the normalised "/"-separated parts of the name in sorted order, so "Rohini Sec 18
   * / Police Chowki" and "Police Chowki / Rohini Sec 18" are one stand.
   */
  static String groupKey(String name) {
    if (name == null) return "";
    return Arrays.stream(name.split("/"))
        .map(StandService::normalize)
        .filter(p -> !p.isEmpty())
        .sorted()
        .reduce((a, b) -> a + " / " + b)
        .orElse("");
  }

  private StandIndex buildIndex() {
    Map<String, List<TransitIndex.Stop>> byKey = new HashMap<>();
    for (var s : index.stops.values()) {
      String key = groupKey(s.name());
      if (!key.isEmpty()) byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
    }
    List<Cluster> stands = new ArrayList<>();
    Map<String, Cluster> byStop = new HashMap<>();
    for (var e : byKey.entrySet()) {
      var stops = new ArrayList<>(e.getValue());
      stops.sort(Comparator.comparing(TransitIndex.Stop::id));
      for (var group : chain(stops)) {
        var c = cluster(group);
        stands.add(c);
        for (var s : group) byStop.put(s.id(), c);
      }
    }
    return new StandIndex(List.copyOf(stands), Map.copyOf(byStop));
  }

  /** Single-link grouping: a stop joins (and merges) every group with a member within range. */
  static List<List<TransitIndex.Stop>> chain(List<TransitIndex.Stop> stops) {
    List<List<TransitIndex.Stop>> groups = new ArrayList<>();
    for (var s : stops) {
      List<TransitIndex.Stop> joined = null;
      for (var it = groups.iterator(); it.hasNext(); ) {
        var g = it.next();
        boolean near = g.stream()
            .anyMatch(m -> Geometry.distance(m.lat(), m.lng(), s.lat(), s.lng()) <= STAND_MERGE_METERS);
        if (!near) continue;
        if (joined == null) joined = g;
        else {
          joined.addAll(g);
          it.remove();
        }
      }
      if (joined == null) groups.add(joined = new ArrayList<>());
      joined.add(s);
    }
    return groups;
  }

  private Cluster cluster(List<TransitIndex.Stop> stops) {
    // Main stop: the one most routes call at.
    var main =
        stops.stream()
            .max(Comparator.comparingInt((TransitIndex.Stop s) -> routesAt(s.id()).size())
                .thenComparing(TransitIndex.Stop::id, Comparator.reverseOrder()))
            .orElseThrow();
    double lat = 0, lng = 0;
    for (var s : stops) {
      lat += s.lat();
      lng += s.lng();
    }
    Set<String> numbers = new TreeSet<>(ROUTE_ORDER);
    for (var s : stops) numbers.addAll(numbersOf(routesAt(s.id())));
    String name = displayName(stops);
    // Matched against the shown spelling; grouping used the order-insensitive groupKey.
    String key = normalize(name);
    return new Cluster(
        main.id(), name, key, key.split(" "), key.replace(" ", ""),
        List.copyOf(stops), lat / stops.size(), lng / stops.size(), List.copyOf(numbers));
  }

  /** The spelling most members use; ties prefer one not in capitals, then the shortest. */
  static String displayName(List<TransitIndex.Stop> stops) {
    Map<String, Integer> counts = new HashMap<>();
    for (var s : stops) counts.merge(s.name().trim().replaceAll("\\s+", " "), 1, Integer::sum);
    return counts.entrySet().stream()
        .min(Comparator.comparingInt((Map.Entry<String, Integer> e) -> -e.getValue())
            .thenComparing(e -> e.getKey().equals(e.getKey().toUpperCase(Locale.ROOT)))
            .thenComparingInt(e -> e.getKey().length())
            .thenComparing(Map.Entry::getKey))
        .map(Map.Entry::getKey)
        .orElse("");
  }

  private Set<String> routesAt(String stopId) {
    return index.routesAtStop.getOrDefault(stopId, Set.of());
  }

  private List<String> numbersOf(Collection<String> routeIds) {
    return routeIds.stream()
        .map(index.routes::get)
        .filter(Objects::nonNull)
        .map(TransitIndex.Route::number)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
  }

  // ── Search ───────────────────────────────────────────────────────────────

  /** Stands whose name matches {@code query}, best match first, each stand once. */
  public List<Stand> search(String query, int limit) {
    String[] q = queryTokens(query);
    if (String.join("", q).length() < MIN_QUERY_CHARS) return List.of();
    int cap = Math.max(1, Math.min(limit, MAX_LIMIT));
    record Hit(Cluster c, int rank) {}
    List<Hit> hits = new ArrayList<>();
    for (var c : standIndex().stands()) {
      int r = rank(c, q);
      if (r >= 0) hits.add(new Hit(c, r));
    }
    hits.sort(
        Comparator.comparingInt(Hit::rank)
            // Busier stands first: "Azadpur" should lead with the terminal, not a side street.
            .thenComparing(h -> -h.c().routeNumbers().size())
            .thenComparingInt(h -> h.c().name().length())
            .thenComparing(h -> h.c().name())
            .thenComparing(h -> h.c().id()));
    var top = hits.stream().limit(cap).map(Hit::c).toList();
    Map<String, Integer> sameName = new HashMap<>();
    for (var c : top) sameName.merge(c.key(), 1, Integer::sum);
    return top.stream().map(c -> c.view(sameName.get(c.key()) > 1 ? landmark(c) : null)).toList();
  }

  /** "near <closest stop with another name>" within 1 km of the stand; null when none. */
  private String landmark(Cluster c) {
    for (var s : index.nearby(c.lat(), c.lng(), 1000))
      if (!normalize(s.name()).equals(c.key())) return "near " + s.name().trim();
    return null;
  }

  /** The stand containing {@code stopId}; 404 for an unknown stop. */
  Cluster standOf(String stopId) {
    var c = standIndex().byStop().get(stopId);
    if (c != null) return c;
    var s = index.stops.get(stopId);
    if (s == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown bus stop ID");
    // A stop whose name normalises to nothing: a stand of its own.
    return cluster(List.of(s));
  }

  // ── Arrivals ─────────────────────────────────────────────────────────────

  /** Live (and, where no live bus is listed, timetable) arrivals at the stand containing the stop. */
  public StandArrivals arrivals(String stopId) {
    var stand = standOf(stopId);
    long generation = live.generation();
    long nowMs = System.currentTimeMillis();
    var cached = cache.get(stand.id());
    if (cached != null && cached.generation() == generation && nowMs - cached.builtAtMs() < CACHE_MS)
      return cached.value();
    var value = arrivals(stand, Instant.ofEpochMilli(nowMs));
    if (cache.size() > 500) cache.clear();
    cache.put(stand.id(), new Cached(generation, nowMs, value));
    return value;
  }

  StandArrivals arrivals(String stopId, Instant now) {
    return arrivals(standOf(stopId), now);
  }

  private record Candidate(RegionService.Upcoming upcoming, TransitIndex.Stop stop) {}

  private StandArrivals arrivals(Cluster stand, Instant now) {
    List<String> warnings = new ArrayList<>();
    // Every route number calling here: the timetable's, plus routes whose buses were seen driving
    // past (learned paths), as on the Live Map.
    Set<String> numbers = new TreeSet<>(ROUTE_ORDER);
    numbers.addAll(stand.routeNumbers());
    for (var s : stand.stops()) numbers.addAll(numbersOf(regions.learnedRoutesAt(s.id())));

    var memo = regions.new Memo();
    Set<String> eligible = new HashSet<>(numbers); // hash lookups: tolerates a null number
    Map<String, Candidate> byVehicle = new LinkedHashMap<>();
    for (var s : stand.stops())
      for (var u : regions.upcoming(s.id(), eligible, warnings, memo)) {
        var prev = byVehicle.get(u.vehicleId());
        if (prev == null || etaOrMax(u) < etaOrMax(prev.upcoming())) byVehicle.put(u.vehicleId(), new Candidate(u, s));
      }

    long nowSec = now.getEpochSecond();
    int nowSod = now.atZone(SERVICE_ZONE).toLocalTime().toSecondOfDay();
    List<BusArrival> arrivals = new ArrayList<>();
    for (var c : byVehicle.values())
      arrivals.add(toArrival(c, memo.vehicles().get(c.upcoming().vehicleId()), stand, nowSec, nowSod));
    arrivals.sort(
        Comparator.comparing(BusArrival::etaMinutes, Comparator.nullsLast(Double::compareTo))
            .thenComparing(BusArrival::routeNumber, ROUTE_ORDER));

    Set<String> withLive = new HashSet<>();
    for (var a : arrivals) withLive.add(a.routeNumber());
    var scheduled = scheduled(stand, withLive, nowSec, nowSod);

    boolean feedAvailable = transit.feedAvailable();
    var state = !feedAvailable ? LiveState.FEED_UNAVAILABLE
        : arrivals.isEmpty() ? LiveState.NO_LIVE_BUSES : LiveState.LIVE;
    String notice = state != LiveState.LIVE && !scheduled.isEmpty() ? SCHEDULED_NOTICE : null;
    if (!feedAvailable && live.feedError != null && !warnings.contains(live.feedError))
      warnings.add(live.feedError);
    long unconfirmed = arrivals.stream().filter(a -> RegionService.CHECK_OFF_ROUTE.equals(a.routeCheck())).count();
    if (unconfirmed > 0)
      warnings.add(unconfirmed + " listed buses are far from every stop of the route number the feed"
          + " gives them, so that number is unconfirmed.");
    if (!scheduled.isEmpty())
      warnings.add("Scheduled times come from the published GTFS timetable. They are not live"
          + " positions, and buses may not run to them.");

    var summary =
        new Summary(
            arrivals.size(),
            (int) arrivals.stream()
                .filter(a -> !a.terminatesHere())
                .filter(a -> a.status().equals(Status.ARRIVING.name()) || a.status().equals(Status.APPROACHING.name()))
                .count(),
            arrivals.stream().filter(a -> a.etaMinutes() != null && !a.terminatesHere()).findFirst().orElse(null),
            scheduled.isEmpty() ? null : scheduled.get(0));
    return new StandArrivals(
        stand.id(), stand.name(), stand.lat(), stand.lng(),
        stand.stops().stream().map(s -> new StandStop(s.id(), s.name(), s.lat(), s.lng())).toList(),
        List.copyOf(numbers), arrivals, scheduled, summary, state.name(), notice, feedAvailable,
        live.fetchedAt, now.toEpochMilli(), warnings);
  }

  private static double etaOrMax(RegionService.Upcoming u) {
    return u.etaMinutes() == null ? Double.MAX_VALUE : u.etaMinutes();
  }

  /** The longest timetable pattern of the route ends at one of the stand's stops. */
  boolean terminatesAt(String routeId, Cluster stand) {
    var last = regions.patterns(routeId).stream()
        .filter(t -> !t.visits().isEmpty())
        .max(Comparator.comparingInt(t -> t.visits().size()))
        .map(t -> t.visits().get(t.visits().size() - 1).stopId())
        .orElse(null);
    return last != null && stand.stops().stream().anyMatch(s -> s.id().equals(last));
  }

  private BusArrival toArrival(
      Candidate c, RealtimeSnapshot.Vehicle v, Cluster stand, long nowSec, int nowSod) {
    var u = c.upcoming();
    var stop = c.stop();
    var route = v == null ? null : transit.route(v);

    Double distance = u.distanceMeters();
    String basis = distance != null ? DISTANCE_ALONG_ROUTE : null;
    if (distance == null && u.busLatitude() != null && u.busLongitude() != null) {
      distance = Geometry.distance(u.busLatitude(), u.busLongitude(), stop.lat(), stop.lng());
      basis = DISTANCE_STRAIGHT_LINE;
    }
    distance = distance == null ? null : Math.round(distance * 10.0) / 10.0;

    long age = Math.max(0, nowSec - u.lastGpsUpdate());
    Double deviation = scheduleDeviation(v, route, stop.id(), u.etaMinutes(), nowSod);
    var status = status(u.etaMinutes(), distance, u.speedKmh(), age, deviation);

    return new BusArrival(
        stop.id(), stop.name(), u.routeNumber(), route == null ? null : destination(route.id()),
        route != null && terminatesAt(route.id(), stand),
        u.vehicleId(), u.busLatitude(), u.busLongitude(),
        u.busLatitude() == null ? null : locationLabel(u.busLatitude(), u.busLongitude()),
        distance, distance == null ? null : Math.round(distance / 10.0) / 100.0, basis,
        u.etaMinutes() == null ? null : Math.round(u.etaMinutes() * 100.0) / 100.0,
        u.availability(), u.label(), u.reason(), status.status().name(), status.reason(),
        deviation == null ? null : Math.round(deviation * 10.0) / 10.0,
        u.speedKmh(), u.remainingStops(), u.lastGpsUpdate(), age, SOURCE_LIVE, u.routeCheck(),
        u.likelyRoutes());
  }

  record StatusResult(Status status, String reason) {}

  /**
   * ARRIVING when within {@value #ARRIVING_METERS} m or {@value #ARRIVING_MINUTES} min; DELAYED when
   * the GPS fix is lagging or the bus is behind its known timetable trip; APPROACHING within
   * {@value #APPROACHING_MINUTES} min; ON_THE_WAY otherwise (including when no time can be
   * estimated). The reason says which rule applied.
   */
  static StatusResult status(
      Double etaMinutes, Double distanceMeters, Double speedKmh, long gpsAgeSeconds, Double deviation) {
    if (gpsAgeSeconds > GPS_LAGGING_SECONDS)
      return new StatusResult(Status.DELAYED,
          "Last GPS update " + gpsAgeSeconds + " s ago, so the bus may have moved since");
    boolean close = distanceMeters != null && distanceMeters <= ARRIVING_METERS;
    if (close || (etaMinutes != null && etaMinutes < ARRIVING_MINUTES))
      return new StatusResult(Status.ARRIVING,
          close ? "Within " + (int) ARRIVING_METERS + " m of the stop" : "Expected within "
              + (int) ARRIVING_MINUTES + " min");
    if (deviation != null && deviation >= BEHIND_SCHEDULE_MINUTES)
      return new StatusResult(Status.DELAYED,
          "About " + Math.round(deviation) + " min behind its timetable trip");
    String stopped = speedKmh != null && speedKmh < STOPPED_KMH ? "Stopped at its last GPS fix; " : "";
    if (etaMinutes == null)
      return new StatusResult(Status.ON_THE_WAY, stopped + "heading here; no reliable time estimate");
    if (etaMinutes <= APPROACHING_MINUTES)
      return new StatusResult(Status.APPROACHING, stopped + "expected within " + (int) APPROACHING_MINUTES + " min");
    return new StatusResult(Status.ON_THE_WAY, stopped + "more than " + (int) APPROACHING_MINUTES + " min away");
  }

  /**
   * Minutes the bus is predicted behind (+) its timetable at the stop. Only when the feed's trip
   * is in the timetable for the bus's own route, and the ETA is known.
   */
  private Double scheduleDeviation(
      RealtimeSnapshot.Vehicle v, TransitIndex.Route route, String stopId, Double eta, int nowSod) {
    if (v == null || route == null || eta == null) return null;
    var trip = index.trips.get(v.tripId());
    if (trip == null || !trip.routeId().equals(route.id())) return null;
    var visit =
        trip.visits().stream()
            .filter(x -> x.stopId().equals(stopId) && (v.sequence() == null || x.sequence() >= v.sequence()))
            .findFirst()
            .orElse(null);
    if (visit == null || visit.arrival() < 0) return null;
    double deviation = (nowSod + eta * 60 - visit.arrival()) / 60.0;
    // Trips running past midnight carry times beyond 24:00.
    while (deviation > 720) deviation -= 1440;
    while (deviation < -720) deviation += 1440;
    return Math.abs(deviation) <= 180 ? deviation : null;
  }

  /** Published destination of a GTFS route_id, else the last stop of its longest stop pattern. */
  String destination(String routeId) {
    String d = index.routeDestinations.get(routeId);
    if (d != null) return d;
    return regions.patterns(routeId).stream()
        .max(Comparator.comparingInt(t -> t.visits().size()))
        .filter(t -> !t.visits().isEmpty())
        .map(t -> index.stops.get(t.visits().get(t.visits().size() - 1).stopId()))
        .map(TransitIndex.Stop::name)
        .orElse(null);
  }

  private String locationLabel(double lat, double lng) {
    var near = index.nearby(lat, lng, LOCATION_LABEL_METERS);
    return near.isEmpty() ? null : "Near " + near.get(0).name();
  }

  // ── Timetable ────────────────────────────────────────────────────────────

  private List<TransitIndex.Trip> trips(String routeId) {
    var built = tripsByRoute;
    if (built == null) {
      synchronized (this) {
        if (tripsByRoute == null) {
          Map<String, List<TransitIndex.Trip>> out = new HashMap<>();
          for (var t : index.trips.values()) out.computeIfAbsent(t.routeId(), k -> new ArrayList<>()).add(t);
          tripsByRoute = out;
        }
        built = tripsByRoute;
      }
    }
    return built.getOrDefault(routeId, List.of());
  }

  /**
   * Timetable departures from the stand's stops in the next {@value #SCHEDULE_WINDOW_MINUTES}
   * minutes, at most {@value #SCHEDULED_PER_ROUTE} per GTFS route_id, for route numbers with no
   * live bus listed. A trip's last stop is skipped: it ends there and cannot be boarded. The feed's
   * calendar has one service running every day, so every trip is taken as running today.
   */
  List<ScheduledArrival> scheduled(Cluster stand, Set<String> withLive, long nowSec, int nowSod) {
    Map<String, TransitIndex.Stop> members = new HashMap<>();
    for (var s : stand.stops()) members.put(s.id(), s);
    Set<String> routeIds = new TreeSet<>();
    for (var s : stand.stops()) routeIds.addAll(routesAt(s.id()));
    int window = SCHEDULE_WINDOW_MINUTES * 60;
    List<ScheduledArrival> out = new ArrayList<>();
    for (String routeId : routeIds) {
      var route = index.routes.get(routeId);
      if (route == null || route.number() == null || withLive.contains(route.number())) continue;
      String destination = destination(routeId);
      List<ScheduledArrival> found = new ArrayList<>();
      for (var trip : trips(routeId)) {
        var visits = trip.visits();
        for (int i = 0; i < visits.size() - 1; i++) {
          var visit = visits.get(i);
          var stop = members.get(visit.stopId());
          if (stop == null) continue;
          int t = visit.departure() >= 0 ? visit.departure() : visit.arrival();
          if (t < 0) continue;
          // Yesterday's trips running past midnight, today's, and tomorrow's early ones.
          for (int day = -1; day <= 1; day++) {
            long until = (long) t + day * 86400L - nowSod;
            if (until < 0 || until > window) continue;
            found.add(new ScheduledArrival(
                stop.id(), stop.name(), route.number(), destination, trip.id(),
                LocalTime.ofSecondOfDay(Math.floorMod(t, 86400)).format(HH_MM),
                nowSec + until, Math.round(until / 6.0) / 10.0, SOURCE_SCHEDULED));
          }
        }
      }
      found.sort(Comparator.comparingDouble(ScheduledArrival::minutesUntil));
      out.addAll(found.subList(0, Math.min(SCHEDULED_PER_ROUTE, found.size())));
    }
    out.sort(Comparator.comparingDouble(ScheduledArrival::minutesUntil)
        .thenComparing(ScheduledArrival::routeNumber, ROUTE_ORDER));
    return List.copyOf(out.subList(0, Math.min(MAX_SCHEDULED, out.size())));
  }
}
