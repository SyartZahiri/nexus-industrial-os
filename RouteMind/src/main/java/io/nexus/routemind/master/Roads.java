package io.nexus.routemind.master;

import io.nexus.contracts.DomainException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The fixed road network (Spec 15a): depot D0 at the plant and 30 customers, with OSRM times and distances. */
public final class Roads {
  public static final String DEPOT = "D0";

  public record Place(int index, String id, String name, double lat, double lon) {}

  private static final List<Place> PLACES;
  private static final Map<String, Place> BY_ID = new HashMap<>(); // lookups only

  static {
    List<Place> list = new ArrayList<>();
    for (int i = 0; i < RoadData.IDS.length; i++) {
      Place p = new Place(i, RoadData.IDS[i], RoadData.NAMES[i], RoadData.LAT[i], RoadData.LON[i]);
      list.add(p);
      BY_ID.put(p.id(), p);
    }
    PLACES = Collections.unmodifiableList(list);
  }

  private Roads() {}

  /** The depot first, then the customers in the order of places.json. */
  public static List<Place> places() {
    return PLACES;
  }

  public static List<Place> customers() {
    return PLACES.subList(1, PLACES.size());
  }

  public static Place place(String id) {
    Place p = id == null ? null : BY_ID.get(id);
    if (p == null) throw DomainException.notFound("Ort", id);
    return p;
  }

  public static int seconds(int from, int to) {
    return RoadData.SECONDS[from][to];
  }

  public static int meters(int from, int to) {
    return RoadData.METERS[from][to];
  }

  public static String attribution() {
    return RoadData.ATTRIBUTION;
  }
}
