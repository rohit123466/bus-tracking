package com.dtc.bus_tracker.live;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Which current DTC route each live route ID's buses are really driving, for the many route IDs
 * whose June-2024 number no longer fits where they go. Compares each path {@link RouteLearner}
 * learned for a route ID with every path in {@link OfficialRoutes}, and keeps the route numbers
 * whose official path contains the whole drive.
 *
 * <p>Runs on its own thread: matching one path takes tens of milliseconds here and far longer on
 * the hosted instance's fraction of a CPU, which must not hold up the feed poll.
 */
@Service
public class RouteIdentifier {
  /** More candidates than this means the drive was only seen on a shared corridor. */
  static final int MAX_CANDIDATES = 3;

  /** The official route numbers consistent with one learned path. */
  public record Guess(RouteLearner.LearnedRoute path, List<String> numbers) {}

  private final RouteLearner learner;
  private final OfficialRoutes official;
  private final Map<String, Guess> guesses = new ConcurrentHashMap<>();
  private ScheduledExecutorService worker;

  @Autowired
  public RouteIdentifier(RouteLearner learner, OfficialRoutes official) {
    this.learner = learner;
    this.official = official;
  }

  @PostConstruct
  void start() {
    if (official.isEmpty()) return;
    worker = Executors.newSingleThreadScheduledExecutor(r -> {
      var t = new Thread(r, "route-identifier");
      t.setDaemon(true);
      t.setPriority(Thread.MIN_PRIORITY);
      return t;
    });
    worker.scheduleWithFixedDelay(this::refreshSafely, 30, 60, TimeUnit.SECONDS);
  }

  @PreDestroy
  void stop() {
    if (worker != null) worker.shutdownNow();
  }

  private void refreshSafely() {
    try {
      refresh();
    } catch (RuntimeException e) {
      System.err.println("[RouteIdentifier] " + e);
    }
  }

  /** Re-matches every learned path that is new or has been replaced since it was last matched. */
  void refresh() {
    for (var path : learner.routes()) {
      if (Thread.currentThread().isInterrupted()) return;
      var known = guesses.get(path.routeId());
      if (known != null && known.path() == path) continue;
      var numbers = official.consistentNumbers(points(path));
      guesses.put(path.routeId(), new Guess(path, numbers == null ? List.of() : numbers));
    }
  }

  private static List<TransitIndex.Point> points(RouteLearner.LearnedRoute path) {
    List<TransitIndex.Point> out = new ArrayList<>(path.points.size());
    for (var p : path.points) out.add(new TransitIndex.Point(p.lat(), p.lng(), out.size()));
    return out;
  }

  /**
   * Official route numbers the route ID's learned drive fits, best first; empty when none was
   * learned yet, nothing fits, or too many routes share that stretch to name a few.
   */
  public List<String> candidates(String routeId) {
    var g = routeId == null ? null : guesses.get(routeId);
    if (g == null || g.numbers().size() > MAX_CANDIDATES) return List.of();
    return g.numbers();
  }

  public int matchedCount() {
    return (int) guesses.values().stream()
        .filter(g -> !g.numbers().isEmpty() && g.numbers().size() <= MAX_CANDIDATES)
        .count();
  }
}
