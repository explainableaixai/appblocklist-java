# AppBlockList for Java

`com.alphaquantum:appblocklist` is a Java 11 client for the App Lookup API at [appblocklist.com](https://www.appblocklist.com). It turns an app identifier into an `AppInfo` object: name, developer, App Store genre, age rating, ranked filtering categories, developer domain and IAB category.

It runs on the JDK's own `java.net.http.HttpClient` and ships its own small JSON reader, so the jar has **no runtime dependencies**. Drop it into a Spring Boot service, a batch job or a plain `main` without dragging in Jackson, Gson or OkHttp versions that clash with yours.

> Source: https://github.com/explainableaixai/appblocklist-java · License: MIT

## Table of contents

- [Adding the dependency](#adding-the-dependency)
- [Hello, app](#hello-app)
- [API reference](#api-reference)
- [Exceptions and status codes](#exceptions-and-status-codes)
- [Spring Boot: checking an app before IT approves it](#spring-boot-checking-an-app-before-it-approves-it)
- [Batch job: enriching an MDM inventory export](#batch-job-enriching-an-mdm-inventory-export)
- [Unit testing code that uses the client](#unit-testing-code-that-uses-the-client)
- [Operational notes](#operational-notes)
- [Why use an app categorization API](#why-use-an-app-categorization-api)
- [Questions and answers](#questions-and-answers)
- [Links](#links) · [Related packages](#related-packages)

---

## Adding the dependency

Maven:

```xml
<dependency>
  <groupId>com.alphaquantum</groupId>
  <artifactId>appblocklist</artifactId>
  <version>1.0.0</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
implementation("com.alphaquantum:appblocklist:1.0.0")
```

Building from source needs JDK 11 or newer and Maven:

```sh
git clone https://github.com/explainableaixai/appblocklist-java
cd appblocklist-java
mvn package
```

The test suite parses recorded API answers, so `mvn test` runs offline and needs no key.

---

## Hello, app

Set your key once in the environment:

```sh
export APPBLOCKLIST_API_KEY=your-key
```

```java
import com.alphaquantum.appblocklist.AppBlockListClient;
import com.alphaquantum.appblocklist.AppInfo;

public class Hello {
    public static void main(String[] args) {
        AppBlockListClient client = AppBlockListClient.fromEnv();
        AppInfo app = client.lookup("com.zhiliaoapp.musically");

        System.out.println(app.getName());                 // TikTok - Videos, Shop & LIVE
        System.out.println(app.getDeveloper());            // TikTok Ltd.
        System.out.println(app.getAgeRating());            // 12+
        System.out.println(app.getFilteringCategories());  // [Social Networking, Streaming Media]
        System.out.println(app.getAndroidPackage());       // com.zhiliaoapp.musically
    }
}
```

`lookup` accepts any of these forms, and an app that exists on both stores comes back with both its iOS and Android identifiers:

| You pass | Kind |
|---|---|
| `com.zhiliaoapp.musically` | iOS bundle ID |
| `835599320` | App Store ID |
| `id835599320` | App Store ID with prefix |
| `com.openai.chatgpt` | Android package name |

A runnable version with a verdict per app is in [`examples/Example.java`](examples/Example.java).

---

## API reference

### `AppBlockListClient`

| Member | Description |
|---|---|
| `static AppBlockListClient fromEnv()` | Key from `APPBLOCKLIST_API_KEY` |
| `static AppBlockListClient create(String apiKey)` | Explicit key |
| `static Builder builder()` | Full configuration |
| `AppInfo lookup(String id)` | One lookup; 404 returns `isFound() == false` |
| `List<LookupOutcome> lookupMany(Iterable<String> ids, Duration pause)` | Sequential lookups, duplicates skipped |
| `static String validateId(String id)` | Local check: trimmed, non-empty, at most 200 characters |
| `static AppInfo parseResponse(int status, String body)` | Same mapping `lookup` uses, for tests and replays |

### `AppBlockListClient.Builder`

| Method | Default |
|---|---|
| `apiKey(String)` | `APPBLOCKLIST_API_KEY` |
| `baseUrl(String)` | `https://www.appblocklist.com/api/v1` |
| `httpClient(HttpClient)` | a new client with a 10 second connect timeout |
| `timeout(Duration)` | 30 seconds per request |
| `retries(int, Duration)` | 2 retries, first wait 1 second, doubled each time |

Every request carries the key in the `X-API-Key` header and the User-Agent `appblocklist-java/1.0.0 (+https://www.appblocklist.com)`.

### `AppInfo`

| Getter | JSON field | Example |
|---|---|---|
| `isFound()` | `found` | `true` |
| `getName()` | `name` | `TikTok - Videos, Shop & LIVE` |
| `getDeveloper()` | `developer` | `TikTok Ltd.` |
| `getBundleId()` | `bundle_id` | `com.zhiliaoapp.musically` |
| `getAppStoreId()` | `app_store_id` | `"835599320"` (a `String`) |
| `getAndroidPackage()` | `android_package` | Google Play twin, or `""` |
| `getAppStoreGenre()` | `app_store_genre` | `Entertainment` |
| `getAgeRating()` | `age_rating` | `12+` |
| `getFilteringCategories()` | `filtering_categories` | unmodifiable, ranked list |
| `getDeveloperDomain()` | `developer_domain` | `tiktok.com` |
| `getIabCategory()` | `iab_category` | `Technology & Computing > ...` |
| `getMessage()` | `error` | `App not found.` on a 404, else `""` |

Convenience methods: `getPrimaryCategory()` (an `Optional<String>`), `inCategory(String...)` and `inCategory(Collection<String>)` (case-insensitive any-of), `isOnGooglePlay()`, `getMinimumAge()` (`12` for `12+`, `-1` if unknown) and `getBlockIds()` (bundle ID plus Android twin). `AppInfo` is immutable and never returns `null` from a getter.

---

## Exceptions and status codes

All exceptions are unchecked and extend `AppBlockListException`, which exposes `getStatusCode()` and `isTemporary()`. The subclasses are nested in that class so one import brings them all in.

| HTTP | Exception | When | Retry? |
|---|---|---|---|
| 400 | `InvalidIdException` | ID missing, malformed or over 200 characters (also thrown locally) | No |
| 401 | `UnauthorizedException` | Key missing or invalid | No |
| 403 | `QuotaExceededException` | Monthly lookups used up; `getMonthlyLookups()` has the count | No |
| 404 | none | `AppInfo.isFound()` is `false` | Not needed |
| 500 | `ServerException` | Temporary error | Yes, done automatically |
| 0 | `AppBlockListException` | Network failure, timeout or no key configured | Network errors are retried |

There are two flavours of 404, and both arrive as a regular `AppInfo`:

```java
AppInfo app = client.lookup("com.example.newapp");
if (!app.isFound()) {
    if (app.isOnGooglePlay()) {
        // "Listed on Google Play, not yet in the categorized database."
        log.info("{} is on Google Play but not categorized yet", app.getAndroidPackage());
    } else {
        // "App not found."
        log.info("unknown app: {}", app.getMessage());
    }
}
```

---

## Spring Boot: checking an app before IT approves it

A common internal tool: staff request an app through a form, and a service decides whether it can be approved automatically, must be denied, or needs a human. The client is thread-safe, so register a single bean.

### Configuration

```java
@Configuration
public class AppBlockListConfig {

    @Bean
    public AppBlockListClient appBlockListClient(
            @Value("${appblocklist.api-key}") String apiKey) {
        return AppBlockListClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofSeconds(10))
                .retries(1, Duration.ofMillis(500))
                .build();
    }
}
```

```yaml
# application.yml
appblocklist:
  api-key: ${APPBLOCKLIST_API_KEY}
policy:
  denied-categories:
    - Social Networking
    - Streaming Media
  max-age: 12
```

### The policy service

```java
@Service
public class AppPolicyService {

    public enum Verdict { APPROVE, DENY, REVIEW }

    public record Decision(String id, Verdict verdict, String reason, AppInfo app) {}

    private final AppBlockListClient client;
    private final List<String> deniedCategories;
    private final int maxAge;

    public AppPolicyService(AppBlockListClient client,
                            @Value("${policy.denied-categories}") List<String> deniedCategories,
                            @Value("${policy.max-age}") int maxAge) {
        this.client = client;
        this.deniedCategories = deniedCategories;
        this.maxAge = maxAge;
    }

    @Cacheable("app-decisions")
    public Decision decide(String id) {
        AppInfo app;
        try {
            app = client.lookup(id);
        } catch (AppBlockListException.InvalidIdException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (AppBlockListException e) {
            return new Decision(id, Verdict.REVIEW, "lookup failed: " + e.getMessage(), null);
        }
        if (!app.isFound()) {
            return new Decision(id, Verdict.REVIEW, app.getMessage(), app);
        }
        if (app.inCategory(deniedCategories)) {
            return new Decision(id, Verdict.DENY,
                    "category " + app.getPrimaryCategory().orElse("?"), app);
        }
        if (app.getMinimumAge() > maxAge) {
            return new Decision(id, Verdict.DENY, "age rating " + app.getAgeRating(), app);
        }
        return new Decision(id, Verdict.APPROVE, "within policy", app);
    }
}
```

The service code uses a Java 16 `record` for brevity; Spring Boot 3 needs Java 17 anyway. The client library itself only needs Java 11.

### The controller

```java
@RestController
@RequestMapping("/apps")
public class AppCheckController {

    private final AppPolicyService policy;

    public AppCheckController(AppPolicyService policy) {
        this.policy = policy;
    }

    @GetMapping("/check")
    public AppPolicyService.Decision check(@RequestParam String id) {
        return policy.decide(id);
    }
}
```

```sh
curl 'http://localhost:8080/apps/check?id=com.zhiliaoapp.musically'
```

```json
{"id":"com.zhiliaoapp.musically","verdict":"DENY","reason":"category Social Networking","app":{...}}
```

`@Cacheable` (with `@EnableCaching` and any cache provider) keeps repeat requests from spending lookups. App categories change slowly, so a cache entry of a day or a week is reasonable. Only 200 answers count against the quota anyway.

### A health indicator that does not spend lookups

The API does not offer a free status endpoint, so a health check should not call it on every probe. Report the result of the last real call instead:

```java
@Component
public class AppBlockListHealth implements HealthIndicator {
    private final AtomicReference<Health> last = new AtomicReference<>(Health.unknown().build());

    public void record(AppBlockListException e) {
        last.set(e == null ? Health.up().build()
                : Health.down().withDetail("status", e.getStatusCode()).withDetail("error", e.getMessage()).build());
    }

    @Override
    public Health health() {
        return last.get();
    }
}
```

---

## Batch job: enriching an MDM inventory export

Jamf Pro, Intune and Google Admin can all export installed apps. The job below reads one ID per line, looks each up once, and writes a CSV your compliance team can open in a spreadsheet. It also writes a plain block list with both the iOS and the Android identifier of every app in a denied category.

```java
import com.alphaquantum.appblocklist.*;
import com.alphaquantum.appblocklist.AppBlockListClient.LookupOutcome;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public class InventoryJob {
    public static void main(String[] args) throws IOException {
        List<String> ids = Files.readAllLines(Path.of(args[0])).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .collect(Collectors.toList());
        Set<String> denied = Set.of("Social Networking", "Streaming Media");

        AppBlockListClient client = AppBlockListClient.fromEnv();
        List<LookupOutcome> outcomes;
        try {
            outcomes = client.lookupMany(ids, Duration.ofMillis(100));
        } catch (AppBlockListException.QuotaExceededException e) {
            System.err.println("Quota reached: " + e.getMonthlyLookups());
            return;
        }

        SortedSet<String> blockList = new TreeSet<>();
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(Path.of("inventory-report.csv")))) {
            csv.println("id,found,name,developer,age_rating,categories,blocked");
            for (LookupOutcome o : outcomes) {
                AppInfo a = o.getApp();
                if (!o.isOk() || !a.isFound()) {
                    csv.printf("%s,false,,,,,%n", o.getId());
                    continue;
                }
                boolean blocked = a.inCategory(denied);
                if (blocked) {
                    blockList.addAll(a.getBlockIds());
                }
                csv.printf("%s,true,\"%s\",\"%s\",%s,\"%s\",%s%n", o.getId(),
                        a.getName().replace("\"", "\"\""), a.getDeveloper().replace("\"", "\"\""),
                        a.getAgeRating(), String.join("; ", a.getFilteringCategories()), blocked);
            }
        }
        Files.write(Path.of("blocked-apps.txt"), blockList);
        System.out.printf("%d apps checked, %d IDs blocked%n", outcomes.size(), blockList.size());
    }
}
```

Importing the result is different for each console. The walkthroughs for [restricting apps with Jamf Pro](https://www.appblocklist.com/mdm/block-apps-jamf.php), [the Intune app block list](https://www.appblocklist.com/mdm/block-apps-intune.php) and [Google Workspace device rules](https://www.appblocklist.com/mdm/google-admin-block-apps.php) show where the file goes. On Android, blocking is done through the managed Google Play and app policy features of [Android Enterprise](https://developer.android.com/work).

If you would rather not run a job at all, the [monthly MDM blocklist feed](https://www.appblocklist.com/mdm-app-blocklist-feed.php) already contains Jamf and Intune import files for all 58 categories.

---

## Unit testing code that uses the client

You do not need a network or a mock framework. `parseResponse` is the exact function `lookup` uses after the HTTP call, so recorded answers are enough to drive your policy logic:

```java
@Test
void deniesSocialApps() {
    AppInfo tiktok = AppBlockListClient.parseResponse(200, Files.readString(Path.of("src/test/resources/tiktok.json")));
    assertEquals(Verdict.DENY, policy.evaluate(tiktok));
}

@Test
void unknownAppsGoToReview() {
    AppInfo missing = AppBlockListClient.parseResponse(404, "{\"found\": false, \"error\": \"App not found.\"}");
    assertEquals(Verdict.REVIEW, policy.evaluate(missing));
}

@Test
void quotaErrorsCarryTheCount() {
    var e = assertThrows(AppBlockListException.QuotaExceededException.class,
            () -> AppBlockListClient.parseResponse(403, "{\"error\":\"Monthly lookups used.\",\"monthly_lookups\":100000}"));
    assertEquals(100000L, e.getMonthlyLookups());
}
```

For end-to-end tests, start the JDK's built-in `com.sun.net.httpserver.HttpServer` on a free port and point the builder's `baseUrl` at it. That covers headers, retries and timeouts without leaving the machine.

---

## Operational notes

- **One request at a time per key.** The service asks for sequential requests, not parallel bursts. `lookupMany` is sequential. If several threads share the client, guard calls with a `Semaphore(1)` or route them through a single-thread executor.
- **Billing.** One lookup is counted per 200 answer. A 404, 400 or 401 costs nothing.
- **Timeouts.** The default per-request timeout is 30 seconds. In a request path, shorten it with `.timeout(Duration.ofSeconds(5))` and fall back to "review" on failure.
- **Proxies.** Pass your own `HttpClient` built with `ProxySelector.of(...)` through `.httpClient(...)`.
- **Logging.** The key is sent as a header, never in the URL, so it does not end up in access logs.

---

## Why use an app categorization API

Policies are written in categories ("no social media on student iPads"), but MDM consoles enforce identifiers ("block `com.zhiliaoapp.musically`"). Somebody has to translate one into the other, and that translation goes stale as new apps ship.

| Without a lookup | With a lookup |
|---|---|
| Hand-maintained spreadsheets of bundle IDs | Categories resolved per app, on demand |
| iOS and Android lists kept separately | One answer carries both identifiers |
| "Why is this blocked?" has no answer | Category, age rating and developer give a reason |

The data covers 1,874,397 iPhone and iPad apps, about 800,000 of them linked to their Android twin, plus 3.9 million tracked Google Play package names. Each app carries one or more of 58 filtering categories, the same scheme that covers 120 million web domains in the [web filtering database](https://www.webfilteringdatabase.com).

The [NIST guidelines on managing the security of mobile devices](https://csrc.nist.gov/pubs/sp/800/124/r2/final) list application vetting and allow or deny lists among the core enterprise controls. For apps used by children, the FTC's [COPPA rule](https://www.ftc.gov/legal-library/browse/rules/childrens-online-privacy-protection-rule-coppa) is a reason schools look closely at what student devices can install. Background on the device side is in Wikipedia's [mobile device management](https://en.wikipedia.org/wiki/Mobile_device_management) article.

### Pricing at a glance

| Product | Price |
|---|---|
| App Lookup API | $99 per month for 100,000 lookups; higher volumes quoted |
| App Blocklists for MDM (school or business) | $99/month or $990/year per organization |
| Districts and IT providers | $249/month or $2,490/year, API with 100,000 lookups a month included |
| Mobile App Database | $999 one-time for the top 100,000 apps, $1,999 one-time for the top 500,000 |

Details are on the [AppBlockList pricing page](https://www.appblocklist.com/pricing.php). Blocklist plans are per organization, not per device.

---

## Questions and answers

### Does this library work on Java 8?

No. It relies on `java.net.http.HttpClient`, which arrived in Java 11. Everything else is plain Java 11 with no extra dependencies.

### Is `AppBlockListClient` thread-safe?

Yes. It is immutable and wraps a thread-safe `HttpClient`. Share one instance. Keep the number of concurrent calls per key at one, as the service asks.

### Why is a 404 not an exception?

Because unknown apps are an expected outcome when you scan real inventories: in-house apps, regional apps and brand new releases. Treating them as data keeps batch loops simple.

### How do I find the bundle ID of an app?

Use the [bundle ID search](https://www.appblocklist.com/tools/bundle-id-lookup.php) for iOS or the [Google Play package lookup](https://www.appblocklist.com/tools/package-name-lookup.php) for Android, both free.

### Which apps do schools usually restrict?

The [school iPad app restriction guide](https://www.appblocklist.com/schools/apps-to-block-on-school-ipads.php) is a good start, together with the lists of [AI assistant apps](https://www.appblocklist.com/lists/ai-apps-list.php) and [VPN apps that bypass filters](https://www.appblocklist.com/lists/vpn-proxy-apps-list.php). For AI services used in a browser, see the [classified AI tool domains](https://www.aitoolsblocklist.com) list.

### Can I get the whole dataset instead of calling the API?

Yes. The [mobile app dataset](https://www.appblocklist.com/mobile-app-database.php) is a licensed file with 12 columns per app, including filtering and IAB categories.

---

## Links

- Website: https://www.appblocklist.com
- API documentation: https://www.appblocklist.com/app-categorization-api.php
- Source code: https://github.com/explainableaixai/appblocklist-java
- Product repository (GitLab): https://gitlab.com/url-classifications/appblocklist
- Contact: https://www.appblocklist.com/contact.php, info@alpha-quantum.com

## Related packages

| Ecosystem | Client |
|---|---|
| npm | [appblocklist](https://www.npmjs.com/package/appblocklist) |
| PyPI | [appblocklist](https://pypi.org/project/appblocklist/) |
| Go | [appblocklist-go](https://github.com/explainableaixai/appblocklist-go) |
| Rust | [appblocklist-rust](https://github.com/explainableaixai/appblocklist-rust) |
| .NET | [appblocklist-dotnet](https://github.com/explainableaixai/appblocklist-dotnet) |

The Node.js and Python packages share the repository https://github.com/explainableaixai/appblocklist.

Also by Alpha Quantum: the [CIPA filtering service for schools and libraries](https://www.cipawebfiltering.com), the [URL classification database](https://www.urlcategorizationdatabase.com) and the [website categorization service](https://www.websitecategorizationapi.com).

## License

MIT License, Copyright (c) 2026 Alpha Quantum. See [LICENSE](LICENSE).
