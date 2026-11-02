package com.pocketbanker.perception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketbanker.security.PerceptionAccess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.locationtech.jts.geom.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Offline, conservative geometry: an uncertain accuracy footprint never invents a city. */
@Component
public class GeoJsonCityResolver implements CityResolver {
  private record Region(City city, String crs, Geometry geometry) {}

  private final List<Region> regions = new ArrayList<>();
  private final GeometryFactory geometry = new GeometryFactory();
  private String version = "unconfigured";

  public GeoJsonCityResolver(
      @Value("${perception.city-boundaries-path:}") String path, ObjectMapper mapper) {
    if (path.isBlank()) return;
    try {
      var file = Path.of(path);
      if (Files.size(file) > 20_000_000) throw new IllegalArgumentException();
      String content = Files.readString(file);
      JsonNode root = mapper.readTree(content);
      version = "sha256:" + PerceptionAccess.hash(content);
      if (!"FeatureCollection".equals(root.path("type").asText()))
        throw new IllegalArgumentException();
      for (JsonNode feature : root.path("features")) {
        var props = feature.path("properties");
        String code = props.path("cityCode").asText(),
            name = props.path("cityName").asText(),
            crs = props.path("coordinateSystem").asText();
        if (code.isBlank()
            || code.length() > 40
            || name.isBlank()
            || name.length() > 100
            || !Set.of("WGS84", "GCJ02").contains(crs)) throw new IllegalArgumentException();
        var shape = feature.path("geometry");
        JsonNode polygons =
            switch (shape.path("type").asText()) {
              case "Polygon" -> mapper.createArrayNode().add(shape.path("coordinates"));
              case "MultiPolygon" -> shape.path("coordinates");
              default -> throw new IllegalArgumentException();
            };
        if (!polygons.isArray() || polygons.isEmpty()) throw new IllegalArgumentException();
        List<Polygon> parts = new ArrayList<>();
        for (var polygon : polygons) {
          if (!polygon.isArray() || polygon.isEmpty()) throw new IllegalArgumentException();
          LinearRing shell = ring(polygon.get(0));
          LinearRing[] holes = new LinearRing[polygon.size() - 1];
          for (int i = 1; i < polygon.size(); i++) holes[i - 1] = ring(polygon.get(i));
          parts.add(geometry.createPolygon(shell, holes));
        }
        Geometry area = geometry.createMultiPolygon(parts.toArray(Polygon[]::new));
        if (!area.isValid() || area.isEmpty()) throw new IllegalArgumentException();
        regions.add(new Region(new City(code, name), crs, area));
      }
    } catch (Exception e) {
      throw new IllegalStateException("invalid_city_boundaries_geojson");
    }
  }

  private LinearRing ring(JsonNode ring) {
    if (!ring.isArray() || ring.size() < 4 || !ring.get(0).equals(ring.get(ring.size() - 1)))
      throw new IllegalArgumentException();
    Coordinate[] points = new Coordinate[ring.size()];
    for (int i = 0; i < ring.size(); i++) {
      var p = ring.get(i);
      if (p.size() != 2 || !p.get(0).isNumber() || !p.get(1).isNumber())
        throw new IllegalArgumentException();
      double x = p.get(0).asDouble(), y = p.get(1).asDouble();
      if (!Double.isFinite(x)
          || !Double.isFinite(y)
          || Math.abs(x) > 180
          || Math.abs(y) > 90
          || i > 0 && Math.abs(x - points[i - 1].x) > 180) throw new IllegalArgumentException();
      points[i] = new Coordinate(x, y);
    }
    return geometry.createLinearRing(points);
  }

  @Override
  public String version() {
    return version;
  }

  @Override
  public Optional<City> resolve(double lat, double lon, String crs) {
    return resolve(lat, lon, crs, 0);
  }

  @Override
  public Optional<City> resolve(double lat, double lon, String crs, double accuracy) {
    if (!Double.isFinite(lat)
        || !Double.isFinite(lon)
        || !Double.isFinite(accuracy)
        || accuracy < 0
        || accuracy > 1000
        || Math.abs(lat) > 85
        || Math.abs(lon) > 180) return Optional.empty();
    // This enclosing rectangle is deliberately larger than the meter accuracy circle.
    // Near poles/dateline, unsupported geometry is unresolved, never silently projected.
    double dy = accuracy / 110000.0;
    double dx = accuracy / (110000.0 * Math.cos(Math.toRadians(Math.abs(lat) + dy)));
    if (Math.abs(lon) + dx >= 180) return Optional.empty();
    Geometry footprint =
        accuracy == 0
            ? geometry.createPoint(new Coordinate(lon, lat))
            : geometry.toGeometry(new Envelope(lon - dx, lon + dx, lat - dy, lat + dy));
    Set<City> matches = new HashSet<>();
    for (var region : regions) {
      if (!region.crs().equals(crs)) continue;
      if (region.geometry().intersects(footprint)) {
        if (!region.geometry().contains(footprint)
            || region.geometry().getBoundary().intersects(footprint)) return Optional.empty();
        matches.add(region.city());
      }
    }
    return matches.size() == 1 ? Optional.of(matches.iterator().next()) : Optional.empty();
  }
}
