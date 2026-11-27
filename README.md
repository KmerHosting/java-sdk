# com.kmerhosting:kmerhosting-sdk

```xml
<dependency>
  <groupId>com.kmerhosting</groupId>
  <artifactId>kmerhosting-sdk</artifactId>
  <version>0.4.0</version>
</dependency>
```

```java
var client = new KmerHostingClient(); // reads KMERHOSTING_API_KEY
var services = client.services().list();
```

Check domain availability and price (public, no API key used, up to 20 domains, 20 searches per minute per IP):

```java
var results = client.domains().search(List.of("example.com", "example.cm")).path("results");
```

The SDK requires Java 17+ and returns Jackson `JsonNode` API envelopes.

Source: https://github.com/KmerHosting/java-sdk
API documentation: https://api.kmerhosting.com/docs

License: Apache-2.0.

## Retries and rate limits

Requests that fail with `429`, `502`, `503` or `504` are retried automatically (3 attempts in total) with exponential backoff and jitter. `Retry-After` is honored, and a write keeps the **same** `Idempotency-Key` on every attempt. When attempts run out on a 429 the client throws `KmerHostingRateLimitException` (a subclass of `KmerHostingApiException`):

```java
try {
  client.services().list();
} catch (KmerHostingRateLimitException e) {
  System.out.println("retry in " + e.retryAfter());
}

// Tune or disable retries:
var tuned = new KmerHostingClient(apiKey, "https://api.kmerhosting.com", Duration.ofSeconds(30),
    HttpClient.newHttpClient(), new ObjectMapper(), "https://domain.kmerhosting.com",
    RetryPolicy.defaults().withMaxAttempts(5)); // RetryPolicy.disabled() turns retries off
```
