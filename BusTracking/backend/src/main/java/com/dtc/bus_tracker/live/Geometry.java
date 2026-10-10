package com.dtc.bus_tracker.live;

import java.util.List;

public final class Geometry {
  private Geometry() {}

  public record Projection(double along, double offset, double bearing) {}

  public static double distance(double a, double b, double c, double d) {
    double x = Math.toRadians(c - a), y = Math.toRadians(d - b);
    double h =
        Math.pow(Math.sin(x / 2), 2)
            + Math.cos(Math.toRadians(a))
                * Math.cos(Math.toRadians(c))
                * Math.pow(Math.sin(y / 2), 2);
    return 6371000 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
  }

  public static Projection project(List<TransitIndex.Point> points, double lat, double lng) {
    double best = Double.POSITIVE_INFINITY, along = 0, total = 0, bearing = 0;
    for (int i = 1; i < points.size(); i++) {
      var a = points.get(i - 1);
      var b = points.get(i);
      double scale = Math.cos(Math.toRadians(lat)),
          x = (b.lng() - a.lng()) * scale,
          y = b.lat() - a.lat();
      double t =
          x * x + y * y == 0
              ? 0
              : Math.max(
                  0,
                  Math.min(
                      1, ((lng - a.lng()) * scale * x + (lat - a.lat()) * y) / (x * x + y * y)));
      double length = distance(a.lat(), a.lng(), b.lat(), b.lng());
      double offset =
          distance(lat, lng, a.lat() + t * (b.lat() - a.lat()), a.lng() + t * (b.lng() - a.lng()));
      if (offset < best) {
        best = offset;
        along = total + t * length;
        bearing = (Math.toDegrees(Math.atan2(x, y)) + 360) % 360;
      }
      total += length;
    }
    return new Projection(along, best, bearing);
  }
}
