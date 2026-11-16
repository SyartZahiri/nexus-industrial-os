package io.nexus.routemind.optimize;

/** Why a delivery could not be planned (Spec 15a). */
public final class Reasons {
  public static final String WINDOW = "Zeitfenster nicht erreichbar";
  public static final String CAPACITY = "Kapazität reicht nicht";
  public static final String SHIFT = "kein Fahrzeug in der Schicht";

  private Reasons() {}
}
