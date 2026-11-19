package ke.carhire.services;

import java.nio.charset.StandardCharsets;
import java.util.*;

final class PortableFormat {
  private PortableFormat() {}

  static final Map<String, String> NAMES =
      Map.ofEntries(
          Map.entry("maintenance", "maintenance_events"),
          Map.entry("conditions", "condition_checks"),
          Map.entry("damages", "fines_damages"));

  static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  static String snake(String s) {
    return s.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
  }
}
