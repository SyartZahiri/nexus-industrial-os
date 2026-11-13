package io.nexus.contracts.json;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Minimal JSON writer for the core (the browser build has no Jackson). Commas are placed automatically.
 * Doubles are written with at most six decimals and no exponent, computed with integer arithmetic only, so the
 * JVM and the browser build produce the same text (Double.toString may choose different digits on each runtime).
 */
public final class JsonWriter {
  private final StringBuilder out = new StringBuilder();
  /** Per open container: true once it has an element. */
  private final Deque<boolean[]> stack = new ArrayDeque<>();
  private boolean afterName;

  public JsonWriter beginObject() {
    separator();
    out.append('{');
    stack.push(new boolean[1]);
    return this;
  }

  public JsonWriter endObject() {
    stack.pop();
    out.append('}');
    return this;
  }

  public JsonWriter beginArray() {
    separator();
    out.append('[');
    stack.push(new boolean[1]);
    return this;
  }

  public JsonWriter endArray() {
    stack.pop();
    out.append(']');
    return this;
  }

  public JsonWriter name(String name) {
    separator();
    string(name);
    out.append(':');
    afterName = true;
    return this;
  }

  public JsonWriter value(String value) {
    separator();
    if (value == null) out.append("null");
    else string(value);
    return this;
  }

  public JsonWriter value(long value) {
    separator();
    out.append(value);
    return this;
  }

  public JsonWriter value(boolean value) {
    separator();
    out.append(value);
    return this;
  }

  public JsonWriter nullValue() {
    separator();
    out.append("null");
    return this;
  }

  /** At most six decimals, trailing zeros removed, "-0" written as "0". */
  public JsonWriter value(double value) {
    if (Double.isNaN(value) || Double.isInfinite(value) || Math.abs(value) >= 9e12) {
      throw new IllegalArgumentException("Zahl nicht als JSON darstellbar: " + value);
    }
    separator();
    long scaled = Math.round(value * 1_000_000);
    if (scaled < 0) out.append('-');
    long abs = Math.abs(scaled);
    out.append(abs / 1_000_000);
    long fraction = abs % 1_000_000;
    if (fraction != 0) {
      String digits = Long.toString(fraction + 1_000_000).substring(1);   // keep leading zeros
      int end = digits.length();
      while (digits.charAt(end - 1) == '0') end--;
      out.append('.').append(digits, 0, end);
    }
    return this;
  }

  /** Inserts already serialised JSON as the next value. */
  public JsonWriter raw(String json) {
    separator();
    out.append(json);
    return this;
  }

  @Override
  public String toString() {
    return out.toString();
  }

  private void separator() {
    if (afterName) {
      afterName = false;
      return;
    }
    if (stack.isEmpty()) return;
    boolean[] hasElement = stack.peek();
    if (hasElement[0]) out.append(',');
    hasElement[0] = true;
  }

  private void string(String s) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            String hex = Integer.toHexString(c);
            out.append("\\u");
            for (int k = hex.length(); k < 4; k++) out.append('0');
            out.append(hex);
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
