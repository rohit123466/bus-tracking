package com.dtc.bus_tracker.live;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import javax.xml.stream.*;

/**
 * Offline importer for DTC's official route list ("All Route Fare Stages",
 * master_data_all_route_fair_stages_1.xlsx from dtc.delhi.gov.in/dtc/services-and-time-table).
 *
 * <p>The workbook lists each route's stops in order for UP and DN but has no coordinates, so every
 * stop name is matched to a GTFS stop by name, and among same-named candidates the one that keeps
 * the route geographically continuous is chosen. The result is written as a CSV that {@link
 * OfficialRoutes} loads at startup; unmatched stops are kept (blank stop_id) so the match rate is
 * visible in the file itself.
 *
 * <p>JDK-only so it runs without Maven, from {@code BusTracking/backend}:
 *
 * <pre>
 * java src/main/java/com/dtc/bus_tracker/live/DtcRouteImport.java \
 *     fare_stages.xlsx src/main/resources/static/GTFS.zip src/main/resources/static/dtc-official-routes.csv.gz
 * </pre>
 *
 * <p>An output name ending in {@code .gz} is gzip-compressed.
 */
public final class DtcRouteImport {
  public record OfficialStop(String name, boolean fareStage) {}

  public record OfficialRoute(String sheet, String number, String direction, List<OfficialStop> stops) {}

  record GtfsStop(String id, String name, double lat, double lng, List<String> tokens) {}

  record Candidate(GtfsStop stop, double score) {}

  /** Minimum name similarity for a GTFS stop to be considered at all. */
  static final double MIN_SCORE = 0.6;
  /** Candidates kept per official stop, best first. */
  static final int MAX_CANDIDATES = 12;
  /** Cost, in route kilometres, of leaving one official stop unmatched. */
  static final double SKIP_KM = 1.5;
  /** Cost, in route kilometres, of a candidate whose name matches with score 0. */
  static final double NAME_KM = 2.0;
  /** How many official stops in a row may be skipped between two matched ones. */
  static final int LOOKBACK = 8;

  private DtcRouteImport() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      System.err.println("usage: DtcRouteImport <fare_stages.xlsx> <GTFS.zip> <out.csv>");
      System.exit(2);
    }
    List<OfficialRoute> routes;
    try (var in = Files.newInputStream(Path.of(args[0]))) {
      routes = readWorkbook(in);
    }
    List<GtfsStop> stops = readGtfsStops(Path.of(args[1]));
    var matcher = new Matcher(stops);

    int total = 0, matched = 0, goodRoutes = 0;
    Map<String, int[]> bySheet = new LinkedHashMap<>();
    OutputStream file = Files.newOutputStream(Path.of(args[2]));
    if (args[2].endsWith(".gz")) file = new GZIPOutputStream(file);
    try (var out = new PrintWriter(new OutputStreamWriter(file, StandardCharsets.UTF_8))) {
      out.println("# DTC official route stops matched to GTFS stops. Generated " + LocalDate.now()
          + " from " + Path.of(args[0]).getFileName() + " by DtcRouteImport.");
      // A route can be listed more than once (the Diversion sheet has several variants of some),
      // so each listed block gets its own path number.
      out.println("path,sheet,route,direction,seq,name,fare_stage,stop_id,lat,lng,score");
      int path = 0;
      for (var route : routes) {
        path++;
        var aligned = matcher.align(route.stops());
        int hits = 0;
        for (int i = 0; i < route.stops().size(); i++) {
          var s = route.stops().get(i);
          var c = aligned.get(i);
          if (c != null) hits++;
          out.println(String.join(",",
              String.valueOf(path), csv(route.sheet()), csv(route.number()), route.direction(), String.valueOf(i + 1),
              csv(s.name()), s.fareStage() ? "1" : "0",
              c == null ? "" : csv(c.stop().id()),
              c == null ? "" : String.valueOf(c.stop().lat()),
              c == null ? "" : String.valueOf(c.stop().lng()),
              c == null ? "" : String.format(Locale.ROOT, "%.2f", c.score())));
        }
        total += route.stops().size();
        matched += hits;
        if (hits >= 0.7 * route.stops().size()) goodRoutes++;
        var sheet = bySheet.computeIfAbsent(route.sheet(), k -> new int[3]);
        sheet[0]++;
        sheet[1] += route.stops().size();
        sheet[2] += hits;
      }
    }
    System.out.printf(Locale.ROOT, "%d route directions, %d stops, %d matched to GTFS (%.1f%%)%n",
        routes.size(), total, matched, 100.0 * matched / Math.max(1, total));
    System.out.printf(Locale.ROOT, "%d route directions have at least 70%% of stops matched%n",
        goodRoutes);
    bySheet.forEach((name, n) -> System.out.printf(Locale.ROOT,
        "  %-14s %4d directions %6d stops %5.1f%% matched%n",
        name, n[0], n[1], 100.0 * n[2] / Math.max(1, n[1])));
  }

  /** Haversine distance; duplicated from {@link Geometry} so the file runs on its own. */
  static double km(double lat1, double lng1, double lat2, double lng2) {
    double x = Math.toRadians(lat2 - lat1), y = Math.toRadians(lng2 - lng1);
    double h = Math.pow(Math.sin(x / 2), 2)
        + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(y / 2), 2);
    return 6371 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
  }

  static String csv(String v) {
    return v.contains(",") || v.contains("\"") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
  }

  // ---------------------------------------------------------------- workbook

  private static final Pattern HEADER =
      Pattern.compile("(?i)^\\s*route\\s+(.+?)\\s+(UP|DN|DOWN)\\s*$");

  /**
   * Reads every sheet laid out as side-by-side "Route &lt;number&gt; UP" / "... DN" blocks: under
   * each header, one column marks fare stages ("Fare Stage") and the column after it holds the
   * stop name. Sheets with any other layout (NCR, DS) yield nothing.
   */
  public static List<OfficialRoute> readWorkbook(InputStream xlsx) throws Exception {
    Map<String, byte[]> parts = new HashMap<>();
    try (var zip = new ZipInputStream(xlsx)) {
      ZipEntry e;
      while ((e = zip.getNextEntry()) != null)
        if (e.getName().endsWith(".xml") || e.getName().endsWith(".rels"))
          parts.put(e.getName(), zip.readAllBytes());
    }
    List<String> shared = sharedStrings(parts.get("xl/sharedStrings.xml"));
    Map<String, String> targets = new HashMap<>();
    forEachElement(parts.get("xl/_rels/workbook.xml.rels"), "Relationship",
        r -> targets.put(r.getAttributeValue(null, "Id"), r.getAttributeValue(null, "Target")));
    List<String[]> sheets = new ArrayList<>(); // {name, part}
    forEachElement(parts.get("xl/workbook.xml"), "sheet", r -> {
      String rel = null;
      for (int i = 0; i < r.getAttributeCount(); i++)
        if (r.getAttributeLocalName(i).equals("id")) rel = r.getAttributeValue(i);
      String target = targets.get(rel);
      if (target != null)
        sheets.add(new String[] {r.getAttributeValue(null, "name"),
            target.startsWith("/") ? target.substring(1) : "xl/" + target});
    });
    List<OfficialRoute> out = new ArrayList<>();
    for (var sheet : sheets) {
      byte[] xml = parts.get(sheet[1]);
      if (xml != null) out.addAll(routesInSheet(sheet[0].trim(), rows(xml, shared)));
    }
    return out;
  }

  static List<OfficialRoute> routesInSheet(String sheet, List<Map<Integer, String>> rows) {
    List<OfficialRoute> out = new ArrayList<>();
    for (int r = 0; r < rows.size(); r++) {
      for (var cell : rows.get(r).entrySet()) {
        var m = HEADER.matcher(cell.getValue());
        if (!m.matches()) continue;
        int col = cell.getKey();
        // Headers are not always in the same column from route to route, so any header row ends
        // the block.
        int end = r + 1;
        while (end < rows.size() && !headerRow(rows.get(end))) end++;
        var body = rows.subList(r + 1, end);
        int marker = markerColumn(body, col);
        if (marker < 0) continue;
        List<OfficialStop> stops = new ArrayList<>();
        for (var row : body) {
          String name = clean(row.get(marker + 1));
          if (name.isEmpty()) continue;
          String mark = row.getOrDefault(marker, "").toLowerCase(Locale.ROOT);
          stops.add(new OfficialStop(name, mark.contains("stage")));
        }
        if (stops.size() >= 2)
          out.add(new OfficialRoute(sheet, clean(m.group(1)),
              m.group(2).equalsIgnoreCase("UP") ? "UP" : "DN", stops));
      }
    }
    return out;
  }

  private static boolean headerRow(Map<Integer, String> row) {
    for (String v : row.values())
      if (HEADER.matcher(v).matches() || v.toLowerCase(Locale.ROOT).contains("sr no")) return true;
    return false;
  }

  /** The column at or just right of the header holding "Fare Stage" marks; -1 when absent. */
  private static int markerColumn(List<Map<Integer, String>> body, int headerCol) {
    for (int c = headerCol; c <= headerCol + 3; c++)
      for (var row : body) {
        String v = row.getOrDefault(c, "").toLowerCase(Locale.ROOT);
        if (v.startsWith("fa") && v.contains("stage")) return c;
      }
    return -1;
  }

  private static String clean(String v) {
    return v == null ? "" : v.replace(' ', ' ').replaceAll("\\s+", " ").trim();
  }

  private interface ElementVisitor {
    void visit(XMLStreamReader r) throws XMLStreamException;
  }

  private static void forEachElement(byte[] xml, String local, ElementVisitor visitor)
      throws XMLStreamException {
    if (xml == null) return;
    var r = xmlReader(xml);
    while (r.hasNext())
      if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals(local))
        visitor.visit(r);
  }

  private static XMLStreamReader xmlReader(byte[] xml) throws XMLStreamException {
    var factory = XMLInputFactory.newInstance();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    return factory.createXMLStreamReader(new ByteArrayInputStream(xml));
  }

  private static List<String> sharedStrings(byte[] xml) throws XMLStreamException {
    List<String> out = new ArrayList<>();
    if (xml == null) return out;
    var r = xmlReader(xml);
    StringBuilder text = null;
    boolean inT = false;
    while (r.hasNext()) {
      int e = r.next();
      if (e == XMLStreamConstants.START_ELEMENT) {
        if (r.getLocalName().equals("si")) text = new StringBuilder();
        else if (r.getLocalName().equals("t")) inT = true;
      } else if (e == XMLStreamConstants.CHARACTERS && inT && text != null) {
        text.append(r.getText());
      } else if (e == XMLStreamConstants.END_ELEMENT) {
        if (r.getLocalName().equals("t")) inT = false;
        else if (r.getLocalName().equals("si")) out.add(text.toString());
      }
    }
    return out;
  }

  /** Sheet rows in order as column index (A = 0) -> text; numbers keep their stored form. */
  static List<Map<Integer, String>> rows(byte[] xml, List<String> shared) throws XMLStreamException {
    List<Map<Integer, String>> out = new ArrayList<>();
    var r = xmlReader(xml);
    Map<Integer, String> row = null;
    int col = -1;
    String type = null;
    StringBuilder value = null;
    while (r.hasNext()) {
      int e = r.next();
      if (e == XMLStreamConstants.START_ELEMENT) {
        switch (r.getLocalName()) {
          case "row" -> row = new TreeMap<>();
          case "c" -> {
            col = column(r.getAttributeValue(null, "r"));
            type = r.getAttributeValue(null, "t");
          }
          case "v", "t" -> value = new StringBuilder();
          default -> {}
        }
      } else if (e == XMLStreamConstants.CHARACTERS && value != null) {
        value.append(r.getText());
      } else if (e == XMLStreamConstants.END_ELEMENT) {
        switch (r.getLocalName()) {
          case "v", "t" -> {
            String v = value.toString();
            if ("s".equals(type)) v = shared.get(Integer.parseInt(v.trim()));
            if (row != null && col >= 0) row.merge(col, v, String::concat);
            value = null;
          }
          case "row" -> out.add(row);
          default -> {}
        }
      }
    }
    return out;
  }

  private static int column(String ref) {
    if (ref == null) return -1;
    int c = 0;
    for (char ch : ref.toCharArray()) {
      if (!Character.isLetter(ch)) break;
      c = c * 26 + (Character.toUpperCase(ch) - 'A' + 1);
    }
    return c - 1;
  }

  // ---------------------------------------------------------------- GTFS stops

  static List<GtfsStop> readGtfsStops(Path gtfsZip) throws IOException {
    try (var zip = new ZipInputStream(Files.newInputStream(gtfsZip))) {
      ZipEntry e;
      while ((e = zip.getNextEntry()) != null) {
        if (!e.getName().equals("stops.txt")) continue;
        var reader = new BufferedReader(new InputStreamReader(zip, StandardCharsets.UTF_8));
        List<String> header = parseCsvLine(reader.readLine().replace("﻿", ""));
        int id = header.indexOf("stop_id"), name = header.indexOf("stop_name"),
            lat = header.indexOf("stop_lat"), lng = header.indexOf("stop_lon");
        List<GtfsStop> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String line;
        while ((line = reader.readLine()) != null) {
          var f = parseCsvLine(line);
          if (f.size() <= Math.max(Math.max(id, name), Math.max(lat, lng))) continue;
          double la = Double.parseDouble(f.get(lat).trim()), ln = Double.parseDouble(f.get(lng).trim());
          // The feed repeats some stops under a second id at the same spot; keep the first.
          if (!seen.add(f.get(name).trim() + "@" + la + "," + ln)) continue;
          out.add(new GtfsStop(f.get(id).trim(), f.get(name).trim(), la, ln, tokens(f.get(name))));
        }
        return out;
      }
    }
    throw new IOException("stops.txt not found in " + gtfsZip);
  }

  static List<String> parseCsvLine(String line) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (quoted) {
        if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          cur.append('"');
          i++;
        } else if (ch == '"') quoted = false;
        else cur.append(ch);
      } else if (ch == '"') quoted = true;
      else if (ch == ',') {
        out.add(cur.toString());
        cur.setLength(0);
      } else cur.append(ch);
    }
    out.add(cur.toString());
    return out;
  }

  // ---------------------------------------------------------------- names

  private static final Map<String, String> SYNONYMS = new HashMap<>();
  private static final Set<String> NOISE =
      Set.of("the", "of", "and", "opp", "near", "nr", "no", "stop", "bus", "t");

  static {
    String[][] groups = {
      {"crossing", "xing", "crosing", "xng", "crossin", "cross"},
      {"block", "blk", "bl"},
      {"sector", "sec", "sect", "secto"},
      {"secretariat", "sectt", "secretariate", "sectt"},
      {"station", "stn", "staion", "statin"},
      {"railway", "rly", "rail"},
      {"road", "rd"},
      {"market", "mkt", "markeet"},
      {"hospital", "hosp", "hospl", "hosptal"},
      {"village", "vill", "vlg", "vil", "villag", "gaon", "gaun"},
      {"pocket", "pkt", "pock"},
      {"extension", "extn", "ext", "extention", "exten"},
      {"colony", "col", "coloney"},
      {"terminal", "terminus", "term", "termnl"},
      {"phase", "ph"},
      {"more", "mor", "mod", "morh"},
      {"chowk", "chouk", "chauk", "chok"},
      {"flyover", "fly"},
      {"school", "sch"},
      {"college", "collage", "colg"},
      {"dispensary", "dispencery", "dispensry"},
      {"police", "ps"},
      {"enclave", "encl", "enc"},
      {"apartment", "apartments", "apptt", "apt", "apts"},
      {"metro", "mtr"},
      {"bridge", "brg"},
      {"main", "mn"},
    };
    for (var g : groups) for (var w : g) SYNONYMS.put(w, g[0]);
  }

  /** Lower-case, abbreviation-normalised tokens of a stop name, order preserved. */
  public static List<String> tokens(String name) {
    String s = name == null ? "" : name.toLowerCase(Locale.ROOT);
    s = s.replace("&", " and ");
    s = s.replaceAll("\\bx[\\s.'-]*ing\\b", " crossing ");
    s = s.replaceAll("[^a-z0-9]+", " ");
    s = s.replaceAll("(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])", " ");
    List<String> raw = new ArrayList<>(Arrays.asList(s.trim().split("\\s+")));
    // "L N J P" / "D B Gupta": dotted initials become one token.
    List<String> merged = new ArrayList<>();
    StringBuilder initials = new StringBuilder();
    for (String t : raw) {
      if (t.length() == 1 && Character.isLetter(t.charAt(0))) {
        initials.append(t);
        continue;
      }
      if (!initials.isEmpty()) {
        merged.add(initials.toString());
        initials.setLength(0);
      }
      merged.add(t);
    }
    if (!initials.isEmpty()) merged.add(initials.toString());
    List<String> out = new ArrayList<>();
    for (String t : merged) {
      if (t.isEmpty()) continue;
      t = SYNONYMS.getOrDefault(t, t);
      if (NOISE.contains(t)) continue;
      if (t.matches("0+[0-9]+")) t = t.replaceFirst("^0+", "");
      out.add(t);
    }
    return out;
  }

  /**
   * Dice similarity of two token lists, ignoring order. Words of five or more letters also match
   * with one typo ("Shalmar" / "Shalimar"), at a small discount; numbers must match exactly.
   */
  public static double similarity(List<String> a, List<String> b) {
    if (a.isEmpty() || b.isEmpty()) return 0;
    List<String> rest = new ArrayList<>(b);
    double hits = 0;
    for (String t : a) {
      int exact = rest.indexOf(t);
      if (exact >= 0) {
        rest.remove(exact);
        hits++;
        continue;
      }
      if (t.length() < 5 || Character.isDigit(t.charAt(0))) continue;
      for (int i = 0; i < rest.size(); i++)
        if (rest.get(i).length() >= 5 && withinOneEdit(t, rest.get(i))) {
          rest.remove(i);
          hits += 0.85;
          break;
        }
    }
    return 2 * hits / (a.size() + b.size());
  }

  static boolean withinOneEdit(String a, String b) {
    if (Math.abs(a.length() - b.length()) > 1) return false;
    int i = 0, j = 0, edits = 0;
    while (i < a.length() && j < b.length()) {
      if (a.charAt(i) == b.charAt(j)) {
        i++;
        j++;
        continue;
      }
      if (++edits > 1) return false;
      if (a.length() > b.length()) i++;
      else if (b.length() > a.length()) j++;
      else {
        i++;
        j++;
      }
    }
    return edits + (a.length() - i) + (b.length() - j) <= 1;
  }

  // ---------------------------------------------------------------- matching

  static final class Matcher {
    private final List<GtfsStop> stops;
    /** Token -> stops containing it, to avoid comparing every name with every stop. */
    private final Map<String, List<GtfsStop>> byToken = new HashMap<>();
    /** Five-letter prefix -> stops, so a typo in a later letter still finds the stop. */
    private final Map<String, List<GtfsStop>> byPrefix = new HashMap<>();
    private final Map<String, List<Candidate>> cache = new HashMap<>();

    Matcher(List<GtfsStop> stops) {
      this.stops = stops;
      for (var s : stops)
        for (var t : new HashSet<>(s.tokens())) {
          byToken.computeIfAbsent(t, k -> new ArrayList<>()).add(s);
          if (t.length() >= 5 && !Character.isDigit(t.charAt(0)))
            byPrefix.computeIfAbsent(t.substring(0, 4), k -> new ArrayList<>()).add(s);
        }
    }

    List<Candidate> candidates(String name) {
      return cache.computeIfAbsent(name, n -> {
        var tokens = tokens(n);
        Set<GtfsStop> pool = new LinkedHashSet<>();
        for (var t : tokens) {
          pool.addAll(byToken.getOrDefault(t, List.of()));
          if (t.length() >= 5 && !Character.isDigit(t.charAt(0)))
            pool.addAll(byPrefix.getOrDefault(t.substring(0, 4), List.of()));
        }
        List<Candidate> out = new ArrayList<>();
        for (var s : pool) {
          double score = similarity(tokens, s.tokens());
          if (score >= MIN_SCORE) out.add(new Candidate(s, score));
        }
        out.sort(Comparator.comparingDouble(Candidate::score).reversed());
        return out.size() > MAX_CANDIDATES ? List.copyOf(out.subList(0, MAX_CANDIDATES)) : out;
      });
    }

    /**
     * Picks at most one GTFS stop per official stop so that the matched sequence is short and the
     * names fit well: a shortest path where moving between consecutive matched stops costs their
     * distance, a weaker name match costs up to {@link #NAME_KM}, and leaving a stop unmatched
     * costs {@link #SKIP_KM}. Same-named stops elsewhere in Delhi are thereby rejected, and so is
     * a lone good name match that would send the route on a detour. The same trade-off leaves a
     * terminal unmatched when it is more than {@link #SKIP_KM} from the next matched stop.
     */
    List<Candidate> align(List<OfficialStop> official) {
      int n = official.size();
      List<List<Candidate>> cands = new ArrayList<>(n);
      for (var s : official) cands.add(candidates(s.name()));
      double[][] cost = new double[n][];
      int[][] fromStop = new int[n][], fromCand = new int[n][];
      for (int i = 0; i < n; i++) {
        int k = cands.get(i).size();
        cost[i] = new double[k];
        fromStop[i] = new int[k];
        fromCand[i] = new int[k];
        for (int c = 0; c < k; c++) {
          var cand = cands.get(i).get(c);
          double emit = (1 - cand.score()) * NAME_KM;
          double best = SKIP_KM * i + emit; // first matched stop of the route
          int bs = -1, bc = -1;
          for (int p = Math.max(0, i - LOOKBACK - 1); p < i; p++)
            for (int q = 0; q < cost[p].length; q++) {
              var prev = cands.get(p).get(q).stop();
              double km = km(prev.lat(), prev.lng(), cand.stop().lat(), cand.stop().lng());
              double total = cost[p][q] + km + SKIP_KM * (i - p - 1) + emit;
              if (total < best) {
                best = total;
                bs = p;
                bc = q;
              }
            }
          cost[i][c] = best;
          fromStop[i][c] = bs;
          fromCand[i][c] = bc;
        }
      }
      double best = SKIP_KM * n; // nothing matched
      int bi = -1, bc = -1;
      for (int i = 0; i < n; i++)
        for (int c = 0; c < cost[i].length; c++) {
          double total = cost[i][c] + SKIP_KM * (n - 1 - i);
          if (total < best) {
            best = total;
            bi = i;
            bc = c;
          }
        }
      List<Candidate> out = new ArrayList<>(Collections.nCopies(n, (Candidate) null));
      while (bi >= 0) {
        out.set(bi, cands.get(bi).get(bc));
        int pi = fromStop[bi][bc], pc = fromCand[bi][bc];
        bi = pi;
        bc = pc;
      }
      return out;
    }
  }
}
