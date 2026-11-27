package com.kmerhosting.sdk;

import java.time.Duration;

/**
 * Automatic retries for HTTP 429, 502, 503 and 504: exponential backoff with jitter, {@code Retry-After}
 * honored, and the same Idempotency-Key reused on every attempt. Enabled by default (3 attempts in total).
 */
public final class RetryPolicy {
  /** Pauses the current thread; replaceable in tests. */
  @FunctionalInterface
  public interface Sleeper { void sleep(Duration duration) throws InterruptedException; }

  private final int maxAttempts;
  private final Duration baseDelay;
  private final Duration maxDelay;
  private final Sleeper sleeper;

  private RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay, Sleeper sleeper) {
    this.maxAttempts = Math.max(1, maxAttempts);
    this.baseDelay = baseDelay;
    this.maxDelay = maxDelay;
    this.sleeper = sleeper;
  }

  /** 3 attempts, 500 ms base delay, 60 s maximum wait. */
  public static RetryPolicy defaults() { return new RetryPolicy(3, Duration.ofMillis(500), Duration.ofSeconds(60), t -> Thread.sleep(t.toMillis())); }

  /** A single attempt: no retries. */
  public static RetryPolicy disabled() { return defaults().withMaxAttempts(1); }

  public RetryPolicy withMaxAttempts(int attempts) { return new RetryPolicy(attempts, baseDelay, maxDelay, sleeper); }
  public RetryPolicy withBaseDelay(Duration delay) { return new RetryPolicy(maxAttempts, delay, maxDelay, sleeper); }
  public RetryPolicy withMaxDelay(Duration delay) { return new RetryPolicy(maxAttempts, baseDelay, delay, sleeper); }
  public RetryPolicy withSleeper(Sleeper replacement) { return new RetryPolicy(maxAttempts, baseDelay, maxDelay, replacement); }

  public int maxAttempts() { return maxAttempts; }

  static boolean retryable(int status) { return status == 429 || status == 502 || status == 503 || status == 504; }

  Duration delay(int attempt, Duration retryAfter) {
    double millis;
    if (retryAfter != null) {
      millis = retryAfter.toMillis() + Math.random() * 250;
    } else {
      double ceiling = Math.min((double) maxDelay.toMillis(), baseDelay.toMillis() * Math.pow(2, attempt - 1));
      millis = Math.random() * ceiling;
    }
    return Duration.ofMillis((long) Math.min((double) maxDelay.toMillis(), millis));
  }

  void sleep(Duration duration) throws InterruptedException { sleeper.sleep(duration); }
}
