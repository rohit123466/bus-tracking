import com.dtc.bus_tracker.live.TransitIndex;
import org.springframework.core.io.FileSystemResource;
import java.lang.reflect.Field;

/** Times TransitIndex.load() on a GTFS zip and reports the heap it retains. */
public class IndexBench {
  public static void main(String[] a) throws Exception {
    int runs = Integer.parseInt(a[1]);
    for (int run = 0; run < runs; run++) {
      var index = new TransitIndex();
      Field f = TransitIndex.class.getDeclaredField("archive");
      f.setAccessible(true);
      f.set(index, new FileSystemResource(a[0]));
      System.gc();
      long before = used();
      long t = System.nanoTime();
      index.load();
      long ms = (System.nanoTime() - t) / 1_000_000;
      System.gc(); System.gc();
      long retained = used() - before;
      System.out.printf("run %d load %d ms, retained %d MB, trips %d, shapes %d%n", run, ms, retained >> 20, index.trips.size(), index.shapes.size());
    }
  }
  static long used() { var r = Runtime.getRuntime(); return r.totalMemory() - r.freeMemory(); }
}
