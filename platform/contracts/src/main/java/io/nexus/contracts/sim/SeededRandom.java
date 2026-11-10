package io.nexus.contracts.sim;

/**
 * SplitMix64. The only source of randomness in the core: java.util.Random is not guaranteed to produce
 * the same sequence on the JVM and in the browser build, this is.
 */
public final class SeededRandom {
  private final long seed;
  private long state;

  public SeededRandom(long seed) {
    this.seed = seed;
    this.state = seed;
  }

  public long nextLong() {
    state += 0x9E3779B97F4A7C15L;
    long z = state;
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  /** Uniform in [0, 1). */
  public double nextDouble() {
    return (nextLong() >>> 11) * 0x1.0p-53;
  }

  /** Uniform in [0, bound). */
  public int nextInt(int bound) {
    if (bound <= 0) throw new IllegalArgumentException("bound muss positiv sein: " + bound);
    return (int) ((nextLong() >>> 1) % bound);
  }

  /** Uniform in [min, max). */
  public double range(double min, double max) {
    return min + nextDouble() * (max - min);
  }

  /**
   * An independent stream derived from the original seed and a label. Each module forks its own stream,
   * so one module drawing more numbers never shifts the sequence of another.
   */
  public SeededRandom fork(String label) {
    long h = 0xCBF29CE484222325L; // FNV-1a
    for (int i = 0; i < label.length(); i++) {
      h ^= label.charAt(i);
      h *= 0x100000001B3L;
    }
    return new SeededRandom(new SeededRandom(seed ^ h).nextLong());
  }
}
