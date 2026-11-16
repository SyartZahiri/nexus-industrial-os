package io.nexus.routemind.master;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class RoadsTest {
  @Test
  void depotFirstThenThirtyCustomers() {
    assertThat(Roads.places()).hasSize(31);
    assertThat(Roads.places().get(0).id()).isEqualTo(Roads.DEPOT);
    assertThat(Roads.customers()).hasSize(30).allSatisfy(c -> assertThat(c.id()).startsWith("K"));
    assertThat(Roads.place("K24").name()).isEqualTo("Kunde Winterthur");
    assertThat(Roads.attribution()).contains("OpenStreetMap");
  }

  /** Every time and distance comes from the road network: none is shorter than the straight line. */
  @Test
  void matrixIsCompleteAndNoAirLine() {
    int n = Roads.places().size();
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < n; j++) {
        if (i == j) {
          assertThat(Roads.seconds(i, j)).isZero();
          continue;
        }
        var a = Roads.places().get(i);
        var b = Roads.places().get(j);
        double air = haversineMeters(a.lat(), a.lon(), b.lat(), b.lon());
        assertThat(Roads.meters(i, j)).as(a.id() + ">" + b.id()).isGreaterThanOrEqualTo((int) (air * 0.98));
        // no faster than 130 km/h on average
        assertThat(Roads.seconds(i, j)).as(a.id() + ">" + b.id()).isGreaterThanOrEqualTo((int) (Roads.meters(i, j) / 36.2));
      }
    }
  }

  /** The map file belongs to the same generator run: same places in the same order. */
  @Test
  void mapFileHasTheSamePlaces() throws Exception {
    String json = Files.readString(Path.of("../FactoryTwin/public/routemind/roads.json"));
    List<String> ids = new ArrayList<>();
    Matcher m = Pattern.compile("\"id\":\"(D0|K\\d\\d)\"").matcher(json);
    while (m.find()) ids.add(m.group(1));
    List<String> expected = new ArrayList<>();
    for (var p : Roads.places()) expected.add(p.id());
    assertThat(ids).isEqualTo(expected);
    assertThat(json).contains("OpenStreetMap");
  }

  private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
    double r = 6_371_000;
    double dLat = Math.toRadians(lat2 - lat1);
    double dLon = Math.toRadians(lon2 - lon1);
    double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
        + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * r * Math.asin(Math.sqrt(h));
  }
}
