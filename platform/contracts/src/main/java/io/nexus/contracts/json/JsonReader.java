package io.nexus.contracts.json;

import io.nexus.contracts.DomainException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader for the core. Objects become LinkedHashMap, arrays ArrayList, integers Long, other numbers
 * Double. Malformed input raises DomainException INVALID naming the position.
 */
public final class JsonReader {
  /** Commands are small; anything longer is refused before parsing. */
  public static final int MAX_LENGTH = 1_000_000;
  /** Deep enough for every message of the platform, shallow enough that recursion never runs out of stack. */
  public static final int MAX_DEPTH = 64;

  private final String s;
  private int pos;
  private int depth;

  private JsonReader(String s) {
    this.s = s;
  }

  public static Object parse(String json) {
    if (json == null) throw DomainException.invalid("JSON fehlt");
    if (json.length() > MAX_LENGTH) throw DomainException.invalid("JSON zu lang: " + json.length() + " Zeichen");
    var r = new JsonReader(json);
    r.skipSpace();
    Object value = r.value();
    r.skipSpace();
    if (r.pos != json.length()) throw r.error("Zeichen nach dem Ende");
    return value;
  }

  private Object value() {
    if (pos >= s.length()) throw error("Wert erwartet");
    char c = s.charAt(pos);
    return switch (c) {
      case '{' -> object();
      case '[' -> array();
      case '"' -> string();
      case 't' -> literal("true", Boolean.TRUE);
      case 'f' -> literal("false", Boolean.FALSE);
      case 'n' -> literal("null", null);
      default -> {
        if (c == '-' || (c >= '0' && c <= '9')) yield number();
        throw error("unerwartetes Zeichen '" + c + "'");
      }
    };
  }

  private Map<String, Object> object() {
    enter();
    pos++;
    Map<String, Object> map = new LinkedHashMap<>();
    skipSpace();
    if (peek() == '}') {
      pos++;
      depth--;
      return map;
    }
    while (true) {
      skipSpace();
      if (peek() != '"') throw error("Feldname erwartet");
      String name = string();
      skipSpace();
      expect(':');
      skipSpace();
      map.put(name, value());
      skipSpace();
      if (peek() == ',') {
        pos++;
        continue;
      }
      expect('}');
      depth--;
      return map;
    }
  }

  private List<Object> array() {
    enter();
    pos++;
    List<Object> list = new ArrayList<>();
    skipSpace();
    if (peek() == ']') {
      pos++;
      depth--;
      return list;
    }
    while (true) {
      skipSpace();
      list.add(value());
      skipSpace();
      if (peek() == ',') {
        pos++;
        continue;
      }
      expect(']');
      depth--;
      return list;
    }
  }

  private String string() {
    pos++;
    StringBuilder out = new StringBuilder();
    while (true) {
      if (pos >= s.length()) throw error("Zeichenkette nicht beendet");
      char c = s.charAt(pos++);
      if (c == '"') return out.toString();
      if (c != '\\') {
        out.append(c);
        continue;
      }
      if (pos >= s.length()) throw error("Escape nicht beendet");
      char e = s.charAt(pos++);
      switch (e) {
        case '"', '\\', '/' -> out.append(e);
        case 'n' -> out.append('\n');
        case 'r' -> out.append('\r');
        case 't' -> out.append('\t');
        case 'b' -> out.append('\b');
        case 'f' -> out.append('\f');
        case 'u' -> {
          if (pos + 4 > s.length()) throw error("Unicode-Escape unvollständig");
          int code = 0;
          for (int i = 0; i < 4; i++) {
            int d = Character.digit(s.charAt(pos++), 16);
            if (d < 0) throw error("Unicode-Escape ungültig");
            code = code * 16 + d;
          }
          out.append((char) code);
          checkSurrogate(out);
        }
        default -> throw error("unbekanntes Escape \\" + e);
      }
    }
  }

  private void enter() {
    if (++depth > MAX_DEPTH) throw error("zu tief verschachtelt (mehr als " + MAX_DEPTH + " Ebenen)");
  }

  /** A unicode escape must not leave a lone surrogate: a high one needs a low one right after it. */
  private void checkSurrogate(StringBuilder out) {
    char last = out.charAt(out.length() - 1);
    boolean high = last >= 0xD800 && last <= 0xDBFF;
    boolean low = last >= 0xDC00 && last <= 0xDFFF;
    if (low) {
      boolean paired = out.length() >= 2 && out.charAt(out.length() - 2) >= 0xD800 && out.charAt(out.length() - 2) <= 0xDBFF;
      if (!paired) throw error("einzelnes Surrogat");
    }
    if (high && !s.startsWith("\\u", pos)) throw error("einzelnes Surrogat");
  }

  private Object number() {
    int start = pos;
    if (peek() == '-') pos++;
    boolean fractional = false;
    while (pos < s.length()) {
      char c = s.charAt(pos);
      if (c >= '0' && c <= '9') {
        pos++;
      } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
        fractional = true;
        pos++;
      } else {
        break;
      }
    }
    String text = s.substring(start, pos);
    try {
      return fractional ? (Object) Double.parseDouble(text) : (Object) Long.parseLong(text);
    } catch (NumberFormatException e) {
      throw error("Zahl ungültig: " + text);
    }
  }

  private Object literal(String word, Object value) {
    if (!s.startsWith(word, pos)) throw error(word + " erwartet");
    pos += word.length();
    return value;
  }

  private void expect(char c) {
    if (peek() != c) throw error("'" + c + "' erwartet");
    pos++;
  }

  private char peek() {
    return pos < s.length() ? s.charAt(pos) : '\0';
  }

  private void skipSpace() {
    while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
  }

  private DomainException error(String what) {
    return DomainException.invalid("JSON ungültig an Position " + pos + ": " + what);
  }
}
