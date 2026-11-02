package com.pocketbanker.perception;

import java.util.Optional;

public interface CityResolver {
  record City(String code, String name) {}

  default Optional<City> resolve(
      double latitude, double longitude, String coordinateSystem, double accuracy) {
    return accuracy == 0 ? resolve(latitude, longitude, coordinateSystem) : Optional.empty();
  }

  default String version() {
    return "unconfigured";
  }

  Optional<City> resolve(double latitude, double longitude, String coordinateSystem);
}
