package io.nexus.contracts.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nexus.contracts.DomainException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {
  @Test
  void writesNestedStructuresWithCommasInTheRightPlaces() {
    String json = new JsonWriter().beginObject()
        .name("id").value("PO-0001").name("qty").value(5).name("big").value(9_007_199_254_740_993L)
        .name("ok").value(true).name("none").nullValue()
        .name("items").beginArray().value(1).value("zwei").beginObject().name("x").value(false).endObject().endArray()
        .name("empty").beginArray().endArray()
        .endObject().toString();
    assertThat(json).isEqualTo("{\"id\":\"PO-0001\",\"qty\":5,\"big\":9007199254740993,\"ok\":true,\"none\":null,"
        + "\"items\":[1,\"zwei\",{\"x\":false}],\"empty\":[]}");
  }

  @Test
  void escapesControlCharactersAndKeepsUmlauts() {
    String json = new JsonWriter().value("Prüfung \"n.i.O.\"\\\n\t\u0001").toString();
    assertThat(json).isEqualTo("\"Prüfung \\\"n.i.O.\\\"\\\\\\n\\t\\u0001\"");
    assertThat(JsonReader.parse(json)).isEqualTo("Prüfung \"n.i.O.\"\\\n\t\u0001");
  }

  /** Fixed precision from integer arithmetic: identical text on the JVM and in the browser build. */
  @Test
  void doublesHaveSixDecimalsAtMostAndNoExponent() {
    assertThat(new JsonWriter().value(0.1 + 0.2).toString()).isEqualTo("0.3");
    assertThat(new JsonWriter().value(1e-7).toString()).isEqualTo("0");
    assertThat(new JsonWriter().value(-12.3456789).toString()).isEqualTo("-12.345679");
    assertThat(new JsonWriter().value(2.0).toString()).isEqualTo("2");
    assertThat(new JsonWriter().value(-0.0000004).toString()).isEqualTo("0");
    assertThat(new JsonWriter().value(12000.5).toString()).isEqualTo("12000.5");
    assertThatThrownBy(() -> new JsonWriter().value(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void readsWhatItWrites() {
    String json = "{\"command\":\"createOrder\",\"quantity\":5,\"share\":0.25,\"tags\":[\"a\",null,true],\"n\":{}}";
    Object parsed = JsonReader.parse(json);
    assertThat(parsed).isEqualTo(Map.of("command", "createOrder", "quantity", 5L, "share", 0.25,
        "tags", java.util.Arrays.asList("a", null, true), "n", Map.of()));
    assertThat(JsonReader.parse(" [ 1 , -2.5e1 ] ")).isEqualTo(List.of(1L, -25.0));
  }

  /** Lone surrogates are not valid UTF-16 text; U+2028/U+2029 break JavaScript string literals. */
  @Test
  void escapesLoneSurrogatesAndLineSeparators() {
    assertThat(new JsonWriter().value("a\uD800b").toString()).isEqualTo("\"a\\ud800b\"");
    assertThat(new JsonWriter().value("x\u2028y\u2029").toString()).isEqualTo("\"x\\u2028y\\u2029\"");
    // a valid pair stays as it is
    assertThat(new JsonWriter().value("\uD83D\uDE00").toString()).isEqualTo("\"\uD83D\uDE00\"");
    assertThatThrownBy(() -> JsonReader.parse("\"\\ud800\"")).isInstanceOf(DomainException.class);
    assertThat(JsonReader.parse("\"\\ud83d\\ude00\"")).isEqualTo("\uD83D\uDE00");
  }

  @Test
  void nestingAndLengthAreBounded() {
    String deep64 = "[".repeat(64) + "]".repeat(64);
    assertThat(JsonReader.parse(deep64)).isNotNull();
    assertThatThrownBy(() -> JsonReader.parse("[".repeat(65) + "]".repeat(65))).isInstanceOf(DomainException.class)
        .hasMessageContaining("tief");
    assertThatThrownBy(() -> JsonReader.parse("[".repeat(100_000))).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> JsonReader.parse("\"" + "x".repeat(JsonReader.MAX_LENGTH) + "\""))
        .isInstanceOf(DomainException.class).hasMessageContaining("lang");
  }

  @Test
  void brokenJsonNamesThePosition() {
    assertThatThrownBy(() -> JsonReader.parse("{\"a\":1,}")).isInstanceOf(DomainException.class)
        .hasMessageContaining("Position 7");
    assertThatThrownBy(() -> JsonReader.parse("{\"a\":1} x")).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> JsonReader.parse("\"offen")).isInstanceOf(DomainException.class);
  }
}
