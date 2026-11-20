# com.kmerhosting:kmerhosting-sdk

```xml
<dependency>
  <groupId>com.kmerhosting</groupId>
  <artifactId>kmerhosting-sdk</artifactId>
  <version>0.3.1</version>
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
