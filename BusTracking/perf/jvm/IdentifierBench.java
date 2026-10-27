import com.dtc.bus_tracker.live.*;
import org.springframework.core.io.FileSystemResource;
import java.lang.reflect.*;

/** Times RouteIdentifier.refresh() (re-matching every learned path to DTC's official paths). */
public class IdentifierBench {
  public static void main(String[] a) throws Exception {
    var index = new TransitIndex();
    var learner = new RouteLearner(index, new LiveTransitService(index, new RealtimeSnapshot()));
    set(learner, "file", a[0]);
    learner.load();
    var official = new OfficialRoutes();
    set(official, "resource", new FileSystemResource(a[1]));
    official.load();
    for (int run = 0; run < Integer.parseInt(a[2]); run++) {
      var identifier = new RouteIdentifier(learner, official);
      Method refresh = RouteIdentifier.class.getDeclaredMethod("refresh");
      refresh.setAccessible(true);
      long t = System.nanoTime();
      refresh.invoke(identifier);
      System.out.printf("run %d refresh %d ms, matched %d of %d%n", run, (System.nanoTime() - t) / 1_000_000, identifier.matchedCount(), learner.routes().size());
    }
  }
  static void set(Object o, String name, Object v) throws Exception {
    Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); f.set(o, v);
  }
}
