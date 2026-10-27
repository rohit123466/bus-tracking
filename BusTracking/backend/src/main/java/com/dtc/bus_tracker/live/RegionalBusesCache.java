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
  public record Encoded(long generation, long builtAtMillis, String etag, byte[] json, byte[] gzip) {}

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
    long generation = live.generation(); // read first: a newer snapshot then forces a rebuild
    var result = regions.buses(key.filter(), key.routeNumber(), key.insideAreaOnly());
    byte[] body = json.writeValueAsBytes(result);
    var crc = new CRC32C();
    crc.update(body);
    String etag = "\"" + Long.toHexString(crc.getValue()) + "-" + body.length + "\"";
    return new Encoded(generation, System.currentTimeMillis(), etag, body, gzip(body));
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
