package com.dtc.bus_tracker.live;

import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32C;
import java.util.zip.GZIPOutputStream;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Encoded {@code /buses/regional} answers, built once per realtime snapshot instead of once per
 * request. Building one walks every live vehicle (route checks, next stop, likely routes) and took
 * ~0.3 s per request; serialising and compressing the ~750 KB answer cost another ~30 ms. Every
 * client polls the same few views every 10 s, and the answer only changes when the snapshot does.
 *
 * <p>An entry is reused while the snapshot generation is unchanged and it is younger than {@link
 * #MAX_AGE_MS}; past that age it is still served once while a background rebuild picks up
 * learner/identifier updates and the 2-minute GPS freshness cut-off. A new snapshot is never
 * answered from an older one: concurrent requests share its one build. The default Live Map view
 * is rebuilt right after each feed update, so polls normally never wait for a build.
 *
 * <p>Each answer is also kept in a compact form ({@link #compact}): the same buses and fields as
 * columns instead of one object per bus, about 40% smaller once gzipped. A client that already
 * holds an answer can {@link #await} the next different one instead of polling for it.
 */
@Component
public class RegionalBusesCache {
  static final long MAX_AGE_MS = 5000;
  long maxAgeMs = MAX_AGE_MS; // tests shorten it
  /** Route numbers are user input; beyond this many views new ones are built but not kept. */
  static final int MAX_ENTRIES = 256;
  /** Fallback poll delay suggested when the feed cadence is unknown or the feed has stalled. */
  static final long DEFAULT_NEXT_MS = 10000;

  record Key(RegionService.Filter filter, String routeNumber, boolean insideAreaOnly) {}

  /** One encoded answer. {@code etag} is a content hash, so an unchanged answer revalidates. */
  public record Encoded(
      long generation, long builtAtMillis, String etag, byte[] json, byte[] gzip,
      /** Gzipped {@link #compact} form of the same answer. */
      byte[] compactGzip,
      /** Milliseconds the answer took to work out and encode. */
      long buildMillis) {
    /** ETag of the compact form: the same content hash, marked so caches keep the two apart. */
    public String compactEtag() {
      return etag.substring(0, etag.length() - 1) + "-c\"";
    }

    /** True when {@code known} (an ETag, with or without quotes) names this answer in either form. */
    public boolean matches(String known) {
      if (known == null || known.isBlank()) return false;
      String k = known.trim();
      if (!k.startsWith("\"")) k = "\"" + k + "\"";
      return k.equals(etag) || k.equals(compactEtag());
    }
  }

  /** A client holding the answer {@code known}, to be told about the next different one. */
  private record Waiter(String known, java.util.function.Consumer<Encoded> onChange) {}

  private final Map<Key, Set<Waiter>> waiters = new ConcurrentHashMap<>();

  private final RegionService regions;
  private final RealtimeSnapshot live;
  private final JsonMapper json;
  private final Map<Key, CompletableFuture<Encoded>> entries = new ConcurrentHashMap<>();
  private final ExecutorService warmer = Executors.newSingleThreadExecutor(r -> {
    var t = new Thread(r, "regional-warmer");
    t.setDaemon(true);
    return t;
  });
  private final AtomicBoolean warming = new AtomicBoolean();

  /** Millis of recent snapshot updates, to predict the next one (see {@link #nextUpdateInMs}). */
  private final Deque<Long> updates = new ArrayDeque<>();

  public RegionalBusesCache(RegionService regions, RealtimeSnapshot live, JsonMapper json) {
    this.regions = regions;
    this.live = live;
    this.json = json;
    live.onVehiclesUpdated(this::snapshotUpdated);
  }

  @PreDestroy
  void shutdown() {
    warmer.shutdownNow();
  }

  public Encoded get(RegionService.Filter filter, String routeNumber, boolean insideAreaOnly) {
    String number = routeNumber == null ? "" : routeNumber.trim().toUpperCase(Locale.ROOT);
    var key = new Key(filter, number, insideAreaOnly);
    while (true) {
      var current = entries.get(key);
      if (current != null) {
        if (!current.isDone()) return join(current); // a build is running: share it
        var e = current.getNow(null);
        if (e != null && fresh(e)) return e;
        // Same snapshot, just older than MAX_AGE_MS: answer now and rebuild in the background,
        // so a poll never waits for a rebuild that brings no new positions.
        if (e != null && e.generation() == live.generation()) {
          refreshInBackground(key, current);
          return e;
        }
      }
      if (current == null && entries.size() >= MAX_ENTRIES) {
        entries.values().removeIf(f -> f.isDone() && !fresh(f.getNow(null)));
        if (entries.size() >= MAX_ENTRIES) return build(key);
      }
      var mine = new CompletableFuture<Encoded>();
      boolean won = current == null ? entries.putIfAbsent(key, mine) == null : entries.replace(key, current, mine);
      if (!won) continue;
      try {
        mine.complete(build(key));
      } catch (RuntimeException | Error e) {
        entries.remove(key, mine);
        mine.completeExceptionally(e);
        throw e;
      }
      return mine.join();
    }
  }

  private final Set<Key> refreshing = ConcurrentHashMap.newKeySet();

  private void refreshInBackground(Key key, CompletableFuture<Encoded> current) {
    if (!refreshing.add(key)) return;
    try {
      warmer.execute(() -> {
        try {
          entries.replace(key, current, CompletableFuture.completedFuture(build(key)));
        } catch (RuntimeException e) {
          System.err.println("[RegionalBusesCache] refresh failed: " + e);
        } finally {
          refreshing.remove(key);
        }
      });
    } catch (RejectedExecutionException e) {
      refreshing.remove(key);
    }
  }

  private boolean fresh(Encoded e) {
    return e != null
        && e.generation() == live.generation()
        && System.currentTimeMillis() - e.builtAtMillis() < maxAgeMs;
  }

  private static Encoded join(CompletableFuture<Encoded> f) {
    try {
      return f.join();
    } catch (CompletionException e) {
      if (e.getCause() instanceof RuntimeException r) throw r;
      throw e;
    }
  }

  private Encoded build(Key key) {
    long started = System.nanoTime();
    long generation = live.generation(); // read first: a newer snapshot then forces a rebuild
    var result = regions.buses(key.filter(), key.routeNumber(), key.insideAreaOnly());
    byte[] body = json.writeValueAsBytes(result);
    var crc = new CRC32C();
    crc.update(body);
    String etag = "\"" + Long.toHexString(crc.getValue()) + "-" + body.length + "\"";
    byte[] compact = gzip(json.writeValueAsBytes(compact(result)));
    var encoded =
        new Encoded(
            generation, System.currentTimeMillis(), etag, body, gzip(body), compact,
            (System.nanoTime() - started) / 1_000_000);
    announce(key, encoded);
    return encoded;
  }

  /**
   * Calls {@code onChange} once, with the first answer for this view that is not {@code known}:
   * right away if the current one already differs, otherwise when a later build produces one.
   * Returns a handle that withdraws the request (call it on timeout or disconnect).
   */
  public Runnable await(
      RegionService.Filter filter, String routeNumber, boolean insideAreaOnly, String known,
      java.util.function.Consumer<Encoded> onChange) {
    String number = routeNumber == null ? "" : routeNumber.trim().toUpperCase(Locale.ROOT);
    var key = new Key(filter, number, insideAreaOnly);
    var fired = new AtomicBoolean();
    var waiter = new Waiter(known, e -> { if (fired.compareAndSet(false, true)) onChange.accept(e); });
    waiters.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(waiter);
    // Registered first, then checked: an answer built in between is not missed.
    var current = entries.get(key);
    var e = current != null && current.isDone() && !current.isCompletedExceptionally() ? current.getNow(null) : null;
    if (e != null && !e.matches(known)) {
      forget(key, waiter);
      waiter.onChange().accept(e);
    }
    return () -> forget(key, waiter);
  }

  private void forget(Key key, Waiter waiter) {
    waiters.computeIfPresent(key, (k, set) -> {
      set.remove(waiter);
      return set.isEmpty() ? null : set;
    });
  }

  /** Hands a freshly built answer to the clients waiting for one that differs from theirs. */
  private void announce(Key key, Encoded encoded) {
    var set = waiters.get(key);
    if (set == null) return;
    for (var w : List.copyOf(set)) {
      if (encoded.matches(w.known())) continue;
      forget(key, w);
      try {
        w.onChange().accept(encoded);
      } catch (RuntimeException e) {
        System.err.println("[RegionalBusesCache] waiter failed: " + e);
      }
    }
  }

  /** Number of clients currently waiting for a new answer. */
  public int waiting() {
    return waiters.values().stream().mapToInt(Set::size).sum();
  }

  static final String[] COLUMNS = {
    "vehicleId", "routeId", "routeCode", "direction", "tripId", "lat", "lng", "speedKmh", "age",
    "mappingSource", "distanceFromShapeMeters", "offRouteFlag", "regions", "nextStopId",
    "nextStopName", "nextStopSource", "nextStopDistanceMeters", "nextStopEtaMinutes",
    "nextStopEtaBasis", "routeCheck", "likelyRoutes"
  };

  /**
   * The same answer with one array per field instead of one object per bus. Nothing is dropped;
   * values that repeat from bus to bus end up next to each other, which is what gzip is good at:
   *
   * <ul>
   *   <li>{@code lat}/{@code lng}: the 5-decimal position times 100,000, as an integer;
   *   <li>{@code age}: {@code fetchedAt - lastUpdatedEpochSeconds};
   *   <li>{@code tripId}: with {@code tripIdPrefixed}, "^rest" stands for routeId + rest;
   *   <li>{@code offRouteFlag}: 1 or 0;
   *   <li>{@code order: "vehicleId"}: the buses were in vehicleId order and are listed grouped by
   *       route here; sort by vehicleId to get that order back. Otherwise the order is as given.
   * </ul>
   */
  static Map<String, Object> compact(RegionService.RegionalBuses r) {
    List<RegionService.RegionalBus> buses = r.buses();
    boolean sorted = true;
    for (int i = 1; i < buses.size() && sorted; i++)
      sorted = buses.get(i - 1).vehicleId().compareTo(buses.get(i).vehicleId()) < 0;
    if (sorted) {
      buses = new ArrayList<>(buses);
      buses.sort(Comparator.comparing(RegionService.RegionalBus::routeId)
          .thenComparing(RegionService.RegionalBus::vehicleId));
    }
    boolean prefixed = true;
    for (var b : buses)
      prefixed &= b.tripId() == null || !b.tripId().startsWith("^") || b.tripId().startsWith(b.routeId());
    Map<String, List<Object>> cols = new LinkedHashMap<>();
    for (String c : COLUMNS) cols.put(c, new ArrayList<>(buses.size()));
    for (var b : buses) {
      String trip = b.tripId();
      if (prefixed && trip != null && !b.routeId().isEmpty() && trip.startsWith(b.routeId()))
        trip = "^" + trip.substring(b.routeId().length());
      cols.get("vehicleId").add(b.vehicleId());
      cols.get("routeId").add(b.routeId());
      cols.get("routeCode").add(b.routeCode());
      cols.get("direction").add(b.direction());
      cols.get("tripId").add(trip);
      cols.get("lat").add(Math.round(b.busLatitude() * 100_000));
      cols.get("lng").add(Math.round(b.busLongitude() * 100_000));
      cols.get("speedKmh").add(b.speedKmh());
      cols.get("age").add(r.fetchedAt() - b.lastUpdatedEpochSeconds());
      cols.get("mappingSource").add(b.mappingSource());
      cols.get("distanceFromShapeMeters").add(b.distanceFromShapeMeters());
      cols.get("offRouteFlag").add(b.offRouteFlag() ? 1 : 0);
      cols.get("regions").add(b.regions());
      cols.get("nextStopId").add(b.nextStopId());
      cols.get("nextStopName").add(b.nextStopName());
      cols.get("nextStopSource").add(b.nextStopSource());
      cols.get("nextStopDistanceMeters").add(b.nextStopDistanceMeters());
      cols.get("nextStopEtaMinutes").add(b.nextStopEtaMinutes());
      cols.get("nextStopEtaBasis").add(b.nextStopEtaBasis());
      cols.get("routeCheck").add(b.routeCheck());
      cols.get("likelyRoutes").add(b.likelyRoutes());
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("compact", 1);
    out.put("warnings", r.warnings());
    out.put("fetchedAt", r.fetchedAt());
    out.put("feedAvailable", r.feedAvailable());
    out.put("region", r.region());
    out.put("eligibleRouteCount", r.eligibleRouteCount());
    out.put("excludedByRegion", r.excludedByRegion());
    out.put("excludedOutsideArea", r.excludedOutsideArea());
    out.put("count", buses.size());
    out.put("order", sorted ? "vehicleId" : "given");
    out.put("tripIdPrefixed", prefixed);
    out.put("cols", cols);
    return out;
  }

  private static byte[] gzip(byte[] body) {
    var out = new ByteArrayOutputStream(body.length / 6 + 64);
    try (var gz = new GZIPOutputStream(out, 8192)) {
      gz.write(body);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  private void snapshotUpdated() {
    synchronized (updates) {
      updates.addLast(System.currentTimeMillis());
      while (updates.size() > 9) updates.removeFirst();
    }
    // Hand off: this runs on the feed thread. Skip if the previous warm-up is still running.
    if (!warming.compareAndSet(false, true)) return;
    try {
      warmer.execute(() -> {
        try {
          get(RegionService.Filter.ANY, "", true);
          // Views with clients waiting on them are brought up to the new snapshot as well.
          for (var key : List.copyOf(waiters.keySet()))
            get(key.filter(), key.routeNumber(), key.insideAreaOnly());
        } catch (RuntimeException e) {
          System.err.println("[RegionalBusesCache] warm-up failed: " + e);
        } finally {
          warming.set(false);
        }
      });
    } catch (RejectedExecutionException e) {
      warming.set(false);
    }
  }

  /**
   * Milliseconds until the next snapshot is expected, from the median gap between recent updates,
   * so clients can poll just after it lands instead of up to a full interval later. Advisory only:
   * {@link #DEFAULT_NEXT_MS} before enough updates were seen or once the feed is overdue by more
   * than two gaps.
   */
  public long nextUpdateInMs() {
    long last, gap;
    synchronized (updates) {
      if (updates.size() < 3) return DEFAULT_NEXT_MS;
      var times = new ArrayList<>(updates);
      long[] gaps = new long[times.size() - 1];
      for (int i = 1; i < times.size(); i++) gaps[i - 1] = times.get(i) - times.get(i - 1);
      Arrays.sort(gaps);
      gap = gaps[gaps.length / 2];
      last = times.get(times.size() - 1);
    }
    long due = last + gap - System.currentTimeMillis();
    if (gap <= 0 || due < -2 * gap) return DEFAULT_NEXT_MS;
    return due;
  }
}
