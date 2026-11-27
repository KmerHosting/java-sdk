# Changelog

## 0.4.0

- Automatic retries for 429, 502, 503 and 504 (3 attempts by default) with exponential backoff and jitter; `Retry-After` is honored.
- Mutating calls reuse the same `Idempotency-Key` on every attempt.
- New `KmerHostingRateLimitException` (extends `KmerHostingApiException`, which is no longer `final`) with `retryAfter()`, `limit()` and `remaining()`.
- New `RetryPolicy` and a constructor overload that accepts it; existing constructors are unchanged.
- OpenAPI copy updated with 429 responses.

## 0.3.1

- Public domain search and spec aligned with the live API.
