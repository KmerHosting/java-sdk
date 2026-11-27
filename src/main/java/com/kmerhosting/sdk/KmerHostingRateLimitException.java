package com.kmerhosting.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;

/** HTTP 429. {@link #retryAfter()} is the server-suggested wait, or null when none was sent. */
public final class KmerHostingRateLimitException extends KmerHostingApiException {
  private final Duration retryAfter;
  private final Integer limit;
  private final Integer remaining;

  public KmerHostingRateLimitException(String message, String code, String requestId, JsonNode body, Duration retryAfter, Integer limit, Integer remaining) {
    super(message, 429, code, requestId, body);
    this.retryAfter = retryAfter;
    this.limit = limit;
    this.remaining = remaining;
  }

  public Duration retryAfter() { return retryAfter; }
  public Integer limit() { return limit; }
  public Integer remaining() { return remaining; }
}
