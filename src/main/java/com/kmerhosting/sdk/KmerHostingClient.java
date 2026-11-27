package com.kmerhosting.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Official, synchronous KmerHosting API v1 client. */
public final class KmerHostingClient {
  private static final String DEFAULT_BASE_URL = "https://api.kmerhosting.com";
  private static final String DEFAULT_DOMAIN_SEARCH_URL = "https://domain.kmerhosting.com";

  private final String apiKey;
  private final String baseUrl;
  private final String domainSearchUrl;
  private final Duration timeout;
  private final HttpClient httpClient;
  private final ObjectMapper mapper;
  private final RetryPolicy retryPolicy;

  public KmerHostingClient() {
    this(System.getenv("KMERHOSTING_API_KEY"));
  }

  public KmerHostingClient(String apiKey) {
    this(apiKey, DEFAULT_BASE_URL, Duration.ofSeconds(30), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), new ObjectMapper());
  }

  public KmerHostingClient(String apiKey, String baseUrl, Duration timeout, HttpClient httpClient, ObjectMapper mapper) {
    this(apiKey, baseUrl, timeout, httpClient, mapper, DEFAULT_DOMAIN_SEARCH_URL);
  }

  /** Same as above, with the public domain search service URL (default https://domain.kmerhosting.com). */
  public KmerHostingClient(String apiKey, String baseUrl, Duration timeout, HttpClient httpClient, ObjectMapper mapper, String domainSearchUrl) {
    this(apiKey, baseUrl, timeout, httpClient, mapper, domainSearchUrl, RetryPolicy.defaults());
  }

  /** Same as above, with an explicit {@link RetryPolicy} ({@link RetryPolicy#disabled()} turns retries off). */
  public KmerHostingClient(String apiKey, String baseUrl, Duration timeout, HttpClient httpClient, ObjectMapper mapper, String domainSearchUrl, RetryPolicy retryPolicy) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException("Set KMERHOSTING_API_KEY or pass an API key to KmerHostingClient.");
    }
    this.apiKey = apiKey;
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.domainSearchUrl = domainSearchUrl.replaceAll("/+$", "");
    this.timeout = timeout;
    this.httpClient = httpClient;
    this.mapper = mapper;
    this.retryPolicy = retryPolicy == null ? RetryPolicy.defaults() : retryPolicy;
  }

  public AccountResource account() { return new AccountResource(this); }
  public ServicesResource services() { return new ServicesResource(this); }
  public DomainsResource domains() { return new DomainsResource(this); }
  public EmailResource email() { return new EmailResource(this); }
  public HostingResource hosting() { return new HostingResource(this); }
  public LxcResource lxc() { return new LxcResource(this); }
  public KvmResource kvm() { return new KvmResource(this); }

  JsonNode get(String path) { return request("GET", path, null, null); }

  JsonNode publicDomainSearch(String pathAndQuery) { return request("GET", domainSearchUrl + pathAndQuery, null, null, false); }

  JsonNode mutate(String method, String path, Object payload, String idempotencyKey) {
    return request(method, path, payload, idempotencyKey == null || idempotencyKey.isBlank() ? UUID.randomUUID().toString() : idempotencyKey);
  }

  private JsonNode request(String method, String path, Object payload, String idempotencyKey) {
    return request(method, baseUrl + path, payload, idempotencyKey, true);
  }

  private JsonNode request(String method, String url, Object payload, String idempotencyKey, boolean authenticated) {
    try {
      HttpRequest.BodyPublisher body = payload == null
          ? HttpRequest.BodyPublishers.noBody()
          : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload));
      HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
          .timeout(timeout)
          .header("Accept", "application/json");
      if (authenticated) builder.header("Authorization", "Bearer " + apiKey);
      if (payload != null) builder.header("Content-Type", "application/json");
      if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
      HttpRequest httpRequest = builder.method(method, body).build();
      HttpResponse<String> response;
      int attempt = 1;
      while (true) {
        // The request (headers and body, including the Idempotency-Key) is reused unchanged on every attempt.
        response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (!RetryPolicy.retryable(response.statusCode()) || attempt >= retryPolicy.maxAttempts()) break;
        retryPolicy.sleep(retryPolicy.delay(attempt, retryAfter(response)));
        attempt++;
      }
      JsonNode parsed = parse(response.body());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        JsonNode error = parsed.path("error");
        if (error.isTextual()) {
          // The public domain-search service answers {"error": "<code>", "message": "..."}.
          error = mapper.createObjectNode().put("code", error.asText()).put("message", parsed.path("message").asText("KmerHosting API request failed with status " + response.statusCode() + "."));
        }
        String requestId = error.path("request_id").asText(response.headers().firstValue("X-Request-Id").orElse(null));
        if (response.statusCode() == 429) {
          throw new KmerHostingRateLimitException(
              error.path("message").asText("KmerHosting API rate limit exceeded."),
              error.path("code").asText("rate_limit_exceeded"),
              requestId,
              parsed,
              retryAfter(response),
              intHeader(response, "X-RateLimit-Limit"),
              intHeader(response, "X-RateLimit-Remaining"));
        }
        throw new KmerHostingApiException(
            error.path("message").asText("KmerHosting API request failed with status " + response.statusCode() + "."),
            response.statusCode(),
            error.path("code").asText("request_failed"),
            requestId,
            parsed
        );
      }
      return parsed;
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("The request body cannot be serialized as JSON.", error);
    } catch (IOException error) {
      throw new KmerHostingApiException("Unable to reach the KmerHosting API.", 0, "connection_failed", null, NullNode.instance);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new KmerHostingApiException("The KmerHosting API request was interrupted.", 0, "request_interrupted", null, NullNode.instance);
    }
  }

  private static Duration retryAfter(HttpResponse<?> response) {
    String value = response.headers().firstValue("Retry-After").orElse(null);
    if (value == null || value.isBlank()) return null;
    try {
      double seconds = Double.parseDouble(value.trim());
      return seconds < 0 ? null : Duration.ofMillis((long) (seconds * 1000));
    } catch (NumberFormatException ignored) {
      try {
        Duration until = Duration.between(ZonedDateTime.now(), ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME));
        return until.isNegative() ? Duration.ZERO : until;
      } catch (RuntimeException invalid) {
        return null;
      }
    }
  }

  private static Integer intHeader(HttpResponse<?> response, String name) {
    try {
      return response.headers().firstValue(name).map(v -> Integer.valueOf(v.trim())).orElse(null);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private JsonNode parse(String body) {
    if (body == null || body.isBlank()) return NullNode.instance;
    try {
      return mapper.readTree(body);
    } catch (JsonProcessingException error) {
      return mapper.createObjectNode().put("data", body);
    }
  }

  static String id(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  public static final class AccountResource {
    private final KmerHostingClient client;
    AccountResource(KmerHostingClient client) { this.client = client; }
    public JsonNode get() { return client.get("/v1/account"); }
    public JsonNode apiUsage() { return client.get("/v1/account/api-usage"); }
  }

  public static final class ServicesResource {
    private final KmerHostingClient client;
    ServicesResource(KmerHostingClient client) { this.client = client; }
    public JsonNode list() { return client.get("/v1/services"); }
    public JsonNode get(String serviceId) { return client.get("/v1/services/" + id(serviceId)); }
  }

  public static final class DomainsResource {
    private final KmerHostingClient client;
    DomainsResource(KmerHostingClient client) { this.client = client; }
    public JsonNode list() { return client.get("/v1/domains"); }
    public JsonNode get(String domainId) { return client.get("/v1/domains/" + id(domainId)); }
    public DnsResource dns() { return new DnsResource(client); }

    /**
     * Checks availability and price of 1 to 20 domains. Public endpoint on domain.kmerhosting.com:
     * no API key is sent; limited to 20 requests per IP per minute (a bulk search counts once).
     * Returns {"results": [...]}.
     */
    public JsonNode search(List<String> domains) {
      List<String> list = domains.stream().map(String::trim).filter(d -> !d.isEmpty()).toList();
      if (list.isEmpty() || list.size() > 20) {
        throw new IllegalArgumentException("Pass between 1 and 20 domains to domains().search().");
      }
      return client.publicDomainSearch("/api/domain-search?domains=" + id(String.join(",", list)));
    }

    public JsonNode search(String domain) { return search(List.of(domain)); }
    public JsonNode setAutoRenew(String domainId, boolean enabled, String idempotencyKey) {
      return client.mutate("PUT", "/v1/domains/" + id(domainId) + "/auto-renew", Map.of("enabled", enabled), idempotencyKey);
    }
    public JsonNode setNameservers(String domainId, List<String> nameServers, String idempotencyKey) {
      return client.mutate("PUT", "/v1/domains/" + id(domainId) + "/nameservers", Map.of("nameServers", nameServers), idempotencyKey);
    }
  }

  public static final class DnsResource {
    private final KmerHostingClient client;
    DnsResource(KmerHostingClient client) { this.client = client; }
    public JsonNode list(String domainId) { return client.get("/v1/domains/" + id(domainId) + "/dns"); }
    public JsonNode create(String domainId, Map<String, Object> record, String idempotencyKey) {
      return client.mutate("POST", "/v1/domains/" + id(domainId) + "/dns", record, idempotencyKey);
    }
    public JsonNode update(String domainId, String recordId, Map<String, Object> record, String idempotencyKey) {
      return client.mutate("PUT", "/v1/domains/" + id(domainId) + "/dns/" + id(recordId), record, idempotencyKey);
    }
    public JsonNode delete(String domainId, String recordId, String idempotencyKey) {
      return client.mutate("DELETE", "/v1/domains/" + id(domainId) + "/dns/" + id(recordId), null, idempotencyKey);
    }
  }

  public static final class EmailResource {
    private final KmerHostingClient client;
    EmailResource(KmerHostingClient client) { this.client = client; }
    public JsonNode listServices() { return client.get("/v1/email/services"); }
    public JsonNode provision(String serviceId, String idempotencyKey) {
      return client.mutate("POST", "/v1/email/services/" + id(serviceId) + "/provision", Map.of(), idempotencyKey);
    }
    public JsonNode syncDns(String serviceId, String idempotencyKey) {
      return client.mutate("POST", "/v1/email/services/" + id(serviceId) + "/dns/sync", Map.of(), idempotencyKey);
    }
  }

  public static final class HostingResource {
    private final KmerHostingClient client;
    HostingResource(KmerHostingClient client) { this.client = client; }
    public JsonNode listServices() { return client.get("/v1/hosting/services"); }
    public JsonNode stats(String serviceId) { return client.get("/v1/hosting/services/" + id(serviceId) + "/stats"); }
    public JsonNode createPanelAccess(String serviceId, String target, String idempotencyKey) {
      return client.mutate("POST", "/v1/hosting/services/" + id(serviceId) + "/panel-access", Map.of("target", target == null ? "panel" : target), idempotencyKey);
    }
  }

  public static final class LxcResource {
    private final KmerHostingClient client;
    LxcResource(KmerHostingClient client) { this.client = client; }
    public JsonNode list() { return client.get("/v1/lxc/instances"); }
    public JsonNode get(String serviceId) { return client.get("/v1/lxc/instances/" + id(serviceId)); }
    public JsonNode metrics(String serviceId) { return client.get("/v1/lxc/instances/" + id(serviceId) + "/metrics"); }
    public JsonNode action(String serviceId, String action, String idempotencyKey) { return client.mutate("POST", "/v1/lxc/instances/" + id(serviceId) + "/actions", Map.of("action", action), idempotencyKey); }
    public JsonNode listSnapshots(String serviceId) { return client.get("/v1/lxc/instances/" + id(serviceId) + "/snapshots"); }
    public JsonNode snapshot(String serviceId, String action, String name, String idempotencyKey) { return client.mutate("POST", "/v1/lxc/instances/" + id(serviceId) + "/snapshots", Map.of("action", action, "name", name), idempotencyKey); }
    public JsonNode changePassword(String serviceId, String password, String idempotencyKey) { return client.mutate("POST", "/v1/lxc/instances/" + id(serviceId) + "/password", Map.of("password", password), idempotencyKey); }
    public JsonNode reinstall(String serviceId, String distribution, String idempotencyKey) { return client.mutate("POST", "/v1/lxc/instances/" + id(serviceId) + "/reinstall", Map.of("distribution", distribution), idempotencyKey); }
    public JsonNode createTerminalTicket(String serviceId, String idempotencyKey) { return client.mutate("POST", "/v1/lxc/instances/" + id(serviceId) + "/terminal-ticket", Map.of(), idempotencyKey); }
    public JsonNode setAutoRenew(String serviceId, boolean enabled, String idempotencyKey) { return client.mutate("PUT", "/v1/lxc/instances/" + id(serviceId) + "/auto-renew", Map.of("enabled", enabled), idempotencyKey); }
    public JsonNode setBillingPeriod(String serviceId, int billingMonths, String idempotencyKey) { return client.mutate("PUT", "/v1/lxc/instances/" + id(serviceId) + "/billing-period", Map.of("billingMonths", billingMonths), idempotencyKey); }
  }

  public static final class KvmResource {
    private final KmerHostingClient client;
    KvmResource(KmerHostingClient client) { this.client = client; }
    public JsonNode list() { return client.get("/v1/kvm/instances"); }
    public JsonNode get(String serviceId) { return client.get("/v1/kvm/instances/" + id(serviceId)); }
    public JsonNode action(String serviceId, KvmAction action, String idempotencyKey) {
      return client.mutate("POST", "/v1/kvm/instances/" + id(serviceId) + "/actions", Map.of("action", action.value()), idempotencyKey);
    }
    public JsonNode setAutoRenew(String serviceId, boolean enabled, String idempotencyKey) {
      return client.mutate("PUT", "/v1/kvm/instances/" + id(serviceId) + "/auto-renew", Map.of("enabled", enabled), idempotencyKey);
    }
    public JsonNode resetPassword(String serviceId, String password, String idempotencyKey) {
      return client.mutate("POST", "/v1/kvm/instances/" + id(serviceId) + "/password", Map.of("password", password), idempotencyKey);
    }
    public JsonNode renew(String serviceId, Integer billingMonths, String idempotencyKey) {
      return client.mutate("POST", "/v1/kvm/instances/" + id(serviceId) + "/renew", billingMonths == null ? Map.of() : Map.of("billingMonths", billingMonths), idempotencyKey);
    }
    public JsonNode cancel(String serviceId, String idempotencyKey) {
      return client.mutate("POST", "/v1/kvm/instances/" + id(serviceId) + "/cancel", Map.of(), idempotencyKey);
    }
    public JsonNode keepService(String serviceId, String idempotencyKey) {
      return client.mutate("POST", "/v1/kvm/instances/" + id(serviceId) + "/keep-service", Map.of(), idempotencyKey);
    }
    public SnapshotsResource snapshots() { return new SnapshotsResource(client); }
  }

  public static final class SnapshotsResource {
    private final KmerHostingClient client;
    SnapshotsResource(KmerHostingClient client) { this.client = client; }
    public JsonNode list(String serviceId) { return client.get("/v1/kvm/instances/" + id(serviceId) + "/snapshots"); }
    public JsonNode create(String serviceId, Map<String, Object> snapshot, String idempotencyKey) {
      return client.mutate("POST", "/v1/kvm/instances/" + id(serviceId) + "/snapshots", snapshot, idempotencyKey);
    }
    public JsonNode update(String serviceId, String snapshotId, Map<String, Object> snapshot, String idempotencyKey) {
      return client.mutate("PATCH", "/v1/kvm/instances/" + id(serviceId) + "/snapshots/" + id(snapshotId), snapshot, idempotencyKey);
    }
    public JsonNode delete(String serviceId, String snapshotId, String idempotencyKey) {
      return client.mutate("DELETE", "/v1/kvm/instances/" + id(serviceId) + "/snapshots/" + id(snapshotId), null, idempotencyKey);
    }
    public JsonNode rollback(String serviceId, String snapshotId, String idempotencyKey) {
      return client.mutate("POST", "/v1/kvm/instances/" + id(serviceId) + "/snapshots/rollback", Map.of("snapshotId", snapshotId), idempotencyKey);
    }
  }
}
