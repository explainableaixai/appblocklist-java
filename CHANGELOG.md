# Changelog

All notable changes to `com.alphaquantum:appblocklist` are listed here.

## 1.0.0 (2026-11-14)

First release.

- `AppBlockListClient` for `GET https://www.appblocklist.com/api/v1/app`, built on `java.net.http.HttpClient` (Java 11+).
- `lookup(id)` accepts an iOS bundle ID, an App Store ID (digits, with or without the `id` prefix) or an Android package name.
- Immutable `AppInfo` result with all response fields and the helpers `getPrimaryCategory`, `inCategory`, `isOnGooglePlay`, `getMinimumAge` and `getBlockIds`.
- HTTP 404 returns an `AppInfo` with `isFound() == false`; 400, 401, 403 and 500 throw `InvalidIdException`, `UnauthorizedException`, `QuotaExceededException` and `ServerException` (all unchecked, extending `AppBlockListException`).
- Retries with backoff for 500 answers and network errors.
- `lookupMany(ids, pause)` runs lookups one after another and skips duplicates.
- Static `parseResponse` and `validateId` for offline tests.
- API key from the builder or the `APPBLOCKLIST_API_KEY` environment variable, sent as the `X-API-Key` header.
- No runtime dependencies: a small JSON reader is included in the package.
- `examples/Example.java`.
