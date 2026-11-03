---
title: "Securing routes with ExerisHttpSecurity"
type: howto
visibility: public
owning-repo: exeris-spring-runtime
status: active
last-verified: 2026-11-03
---

# Securing routes with ExerisHttpSecurity

This guide declares per-path rules that the Exeris kernel enforces on bearer tokens: one public route,
one route that needs a scope, and everything else behind a verified token.

**Mode:** Pure Mode. `ExerisHttpSecurity` is mode-neutral (ADR-063), so the same declaration
governs a Compatibility Mode application's ingress too.

**The edge decides before Spring runs.** The runtime compiles the declaration once at startup into the
kernel's route policy. The kernel verifies the token and checks the rule on its admission path, before
the request reaches a dispatcher. A refused request never enters Spring: no handler, no argument
resolver and no `@Transactional` advice runs for it.

The finished application is [`examples/secured-routes`](../../examples/secured-routes). Its tests boot it
and call it over a real socket with signed tokens.

## Prerequisites

- A Pure Mode application that serves requests. [Getting started](getting-started.md) builds one.
- An OAuth 2.0 issuer that signs JWTs with RSA keys and publishes them as a JWKS document. You need its
  JWKS URI, its issuer (`iss`) value, and the audience (`aud`) value your tokens carry.
- Tokens that carry their scopes in a `scope` claim (space-delimited) or an `scp` claim.

## 1. Declare the rules

Declare an `ExerisHttpSecurity` bean:

<!-- example: secured-routes/src/main/java/eu/exeris/examples/security/RouteSecurityConfiguration.java#policy -->
```java
@Bean
ExerisHttpSecurity httpSecurity() {
    return ExerisHttpSecurity.create()
            // A closed anyRequest() must permit the kernel probe routes, or startup fails.
            .requestMatchers("/health", "/health/live", "/health/ready", "/db/ping", "/db/roundtrip")
                .permitAll()
            .requestMatchers("/catalog").permitAll()
            .requestMatchers("/orders").hasAnyScope("orders:read")
            .anyRequest().authenticated();
}
```

Rules match in declaration order and the first match wins.

| Method | Admits |
|:--|:--|
| `permitAll()` | Any caller. The kernel does not look at the token. |
| `authenticated()` | A caller with a verified token, whatever its scopes. |
| `hasAnyScope(...)` | A verified token that carries at least one of the scopes. |
| `hasAllScopes(...)` | A verified token that carries all of the scopes. |

`requestMatchers(HttpMethod.GET, "/orders")` limits a rule to one method.

Two declarations fail startup:

- **No `anyRequest()` clause.** It has no default. `anyRequest().authenticated()` closes undeclared
  paths; `anyRequest().permitAll()` leaves them open.
- **A closed `anyRequest()` that does not permit the probe routes.** When `anyRequest()` is anything
  other than `permitAll()`, `/health`, `/health/live`, `/health/ready`, `/db/ping` and `/db/roundtrip`
  must each be declared `permitAll()`. The startup error names each one that is not.

**Scopes only, no roles.** There is no `hasRole`. The kernel's route contract checks scopes and nothing
else. Encoding roles as scopes by a naming convention would create a second authority model next to
Spring's, and an edge rule and a Spring check could then disagree about the same caller. Put role checks
in `@PreAuthorize` instead.

## 2. Give the edge its verification keys

Add the `security` subsystem and the three keys to `application.properties`:

<!-- example: secured-routes/src/main/resources/application.properties#properties -->
```properties
# The security subsystem binds the provider that verifies bearer tokens at the edge.
exeris.runtime.subsystems=memory,security,transport,http

# The edge's verification keys. Set all three or none: a partial set fails the kernel boot.
security.jwks.uri=https://issuer.example.com/.well-known/jwks.json
security.jwt.issuer=https://issuer.example.com
security.jwt.audience=orders-api
```

| Property | Meaning |
|:--|:--|
| `security.jwks.uri` | The JWKS document tokens are verified against. An absolute `http` or `https` URI with a host. |
| `security.jwt.issuer` | The `iss` value a token must carry. |
| `security.jwt.audience` | The `aud` value a token must carry. |

The kernel reads these names unchanged from the Spring `Environment`, so any Spring property source can
set them, `@DynamicPropertySource` included. The kernel fetches the key set on the first token and rotates it
itself.

**All three or none:**

- **None set.** The edge holds no key and refuses every token. Every route that needs an identity
  answers `401`; `permitAll()` routes still answer.
- **One or two set.** The kernel boot fails, and the application fails to start at once rather than
  at the end of the startup timeout. The start failure names the error code, the subsystem, the phase
  and the first missing key:

  ```text
  Exeris runtime startup failed: [EX-BOOT-0002] subsystem 'security' failed in INITIALIZE:
  security.jwt.issuer is not set, but another token verification key is; set security.jwks.uri,
  security.jwt.issuer and security.jwt.audience together, or none of them
  ```

  Its cause chain holds the kernel's `SubsystemException`, whose `errorCode()`, `subsystemName()` and
  `detail()` carry the same values. The kernel's own startup banner says only `Subsystem lifecycle
  failure`; read the start failure, not the banner.

**These are not `spring.security.oauth2.resourceserver.jwt.*`.** Those properties configure only the
Compatibility Mode `JwtDecoder`, and they never reach the edge. An application that verifies tokens at
both layers sets both groups, against the same issuer.

## 3. Write the handlers

Each route is an ordinary `ExerisRequestHandler`. The handler carries no security code, because a
request only reaches it after the edge has admitted it:

<!-- example: secured-routes/src/main/java/eu/exeris/examples/security/OrdersHandler.java#handler -->
```java
@Component
@ExerisRoute(method = HttpMethod.GET, path = "/orders")
public class OrdersHandler implements ExerisRequestHandler {

    @Override
    public ExerisServerResponse handle(ExerisServerRequest request) {
        return ExerisServerResponse.ok().body("orders");
    }
}
```

The example also has `/catalog`, which is public, and `/account`, which falls under
`anyRequest().authenticated()`.

## 4. Test it against a real token

Add the kernel testkit in test scope. It signs tokens with a fixed RSA key and brings `nimbus-jose-jwt`
to publish that key:

<!-- example: secured-routes/pom.xml#test-dependencies -->
```xml
<!-- Signs test tokens with a fixed RSA key and exposes its public half. Brings
     nimbus-jose-jwt, which renders that key as a JWKS document. -->
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-kernel-community-testkit</artifactId>
    <version>0.13.1</version>
    <scope>test</scope>
</dependency>
```

Serve the testkit's public key from a loopback JWKS endpoint, and point the three keys at it:

<!-- example: secured-routes/src/test/java/eu/exeris/examples/security/SecuredRoutesApplicationTest.java#jwks-server -->
```java
private static final HttpServer JWKS = startJwksServer();

private static HttpServer startJwksServer() {
    byte[] jwks = new JWKSet(new RSAKey.Builder(TestJwt.testPublicKey()).keyID(TestJwt.TEST_KID).build())
            .toString()
            .getBytes(StandardCharsets.UTF_8);
    try {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(jwks);
            }
        });
        server.start();
        return server;
    } catch (IOException ex) {
        throw new IllegalStateException(ex);
    }
}

@DynamicPropertySource
static void edgeKeys(DynamicPropertyRegistry registry) {
    registry.add("exeris.runtime.network.port", () -> PORT);
    registry.add("security.jwks.uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    registry.add("security.jwt.issuer", () -> TestJwt.EXPECTED_ISSUER);
    registry.add("security.jwt.audience", () -> TestJwt.EXPECTED_AUDIENCE);
}
```

Then assert each answer over the socket. `TestJwt.builder()` signs a token with the testkit's issuer and
audience; `claim("scope", ...)` sets its scopes:

<!-- example: secured-routes/src/test/java/eu/exeris/examples/security/SecuredRoutesApplicationTest.java#assertions -->
```java
@Test
void aPublicRouteNeedsNoToken() throws Exception {
    assertThat(get("/catalog", null).statusCode()).isEqualTo(200);
}

@Test
void aScopedRouteRefusesARequestWithNoToken() throws Exception {
    assertThat(get("/orders", null).statusCode()).isEqualTo(401);
}

@Test
void aScopedRouteRefusesATokenWithoutTheScope() throws Exception {
    assertThat(get("/orders", tokenWithScope("profile")).statusCode()).isEqualTo(403);
}

@Test
void aScopedRouteAdmitsATokenWithTheScope() throws Exception {
    HttpResponse<String> response = get("/orders", tokenWithScope("orders:read"));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("orders");
}

@Test
void anUndeclaredRouteNeedsAnyVerifiedToken() throws Exception {
    assertThat(get("/account", null).statusCode()).isEqualTo(401);
    assertThat(get("/account", tokenWithScope("profile")).statusCode()).isEqualTo(200);
}

@Test
void aTokenFromAnotherIssuerIsRefused() throws Exception {
    String foreign = TestJwt.builder().issuer("https://other.example.com").claim("scope", "orders:read").serialize();

    assertThat(get("/orders", foreign).statusCode()).isEqualTo(401);
}

private static String tokenWithScope(String scope) {
    return TestJwt.builder().claim("scope", scope).serialize();
}
```

A missing or invalid token answers `401`; a verified token without the scope answers `403`.

One Exeris runtime runs per JVM. A second one started while the first runs fails to start at once,
with `Only one Exeris runtime per JVM is supported; stop the earlier runtime before starting another.`
A runtime that stopped releases the JVM, so contexts that start one after another work. The Spring test
context cache keeps a `@SpringBootTest` context, and its runtime, running until the JVM exits, so a test
class that boots the application with a configuration of its own needs a fresh fork. The example sets
surefire's `reuseForks` to `false` for that reason.

## Compatibility Mode: the edge admits, the handler still decides

In a Compatibility Mode application the same `ExerisHttpSecurity` bean governs ingress, and method
security still runs inside the handler. A request the edge admits on `authenticated()` still meets
`@PreAuthorize` on the controller method under `@EnableMethodSecurity`, and is refused there with `403`
when the Spring `Authentication` lacks the authority. Spring builds that `Authentication` with its own
`JwtDecoder`, which `spring.security.oauth2.resourceserver.jwt.*` or your own bean provides. See
[Spring Security in Compatibility Mode](../architecture/compat-spring-security-support.md) for the
support table and the startup checks.

**The edge and Spring map the same token separately.** The kernel takes scopes from the token through its
own `ClaimsMapper`, which reads the `scope` and `scp` claims. Spring takes authorities through your
`Converter<Jwt, …>`. For a token whose authorities do not come from a literal `scope` or `scp` claim,
for example Keycloak's `realm_access.roles`, the two can disagree: an edge rule can refuse a caller that
`@PreAuthorize` would admit, or the other way round. Both fail closed, so a disagreement is a refusal and
never an escalation. When a `403` does not match the roles you expect, check which layer answered.

## Where this does not apply

- **Role checks at the edge.** There is no `hasRole` or `hasAuthority`. Use `@PreAuthorize`.
- **`SecurityFilterChain`.** It cannot run, because there is no servlet container. In Compatibility
  Mode the runtime fails startup when it finds one. Rewrite its `authorizeHttpRequests` rules as an `ExerisHttpSecurity`
  declaration.
- **Opaque tokens and introspection.** The edge verifies signed JWTs against a JWKS document only.
- **Sessions, form login, HTTP Basic, CORS, security headers.** Terminate these in front of the
  application.
- **No `ExerisHttpSecurity` bean.** The kernel then applies no per-route rule, and every route is served
  as before.

## Related

- [Getting started](getting-started.md) — the Pure Mode application this guide secures.
- [Migrating a Spring MVC application](compatibility-mode-migration.md) — moving an existing security
  configuration onto Exeris.
- [Configuration reference](configuration-reference.md) — every `exeris.runtime.*` property.
