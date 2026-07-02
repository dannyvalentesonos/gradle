# Gradle Script Download Provider

[`script-provider.gradle.kts`](script-provider.gradle.kts) downloads Gradle
script files from a **private GitHub repository** at configuration time and
caches them on disk, so shared build logic can be versioned in one repo and
pulled into many projects without vendoring it.

Files are fetched from [`raw.githubusercontent.com`] using a Bearer token and
the `Accept: application/vnd.github.raw` header. (The `api.github.com` Contents
API is the "preferred" GitHub endpoint but benchmarks ~3× slower, so the raw
host is used instead — see the `TODO` in the source for how to switch back.)

It exposes **two entry points**, both registered on `extra`:

- **`getGradleScriptDownloadProvider`** — the primary, configuration-cache-aware
  path. Returns a Gradle [`ValueSource`] `Provider<String>`, making the download
  a first-class **configuration-cache input**: the build re-runs the dependent
  logic only when the remote file actually changes (via `ETag`).
- **`downloadGradleScript`** — a simpler, eager download that runs inline. It
  downloads only when the target is missing (or `forceDownload` is set) and does
  **not** do `ETag`-based change detection.

### How the provider works (`getGradleScriptDownloadProvider`)

1. The file is fetched at a specific ref (branch, tag, or commit SHA),
   requesting the raw bytes.
2. The response `ETag` is stored in a sidecar file next to the download
   (`<targetFile>.etag`).
3. On subsequent runs the saved `ETag` is replayed via the `If-None-Match`
   header. An unchanged ref returns `304 Not Modified` and the cached file is
   reused — no re-download.
4. The `ETag` is returned from the `ValueSource`. Calling `.get()` on the
   provider records that value as a configuration-cache input, so `obtain()`
   re-runs every build and a changed remote ref invalidates the cache (and
   re-applies the downloaded script) without a manual version bump.

### Caching & offline behavior

| Remote response        | Behavior                                                      |
| ---------------------- | ------------------------------------------------------------ |
| `200 OK`               | File is (over)written; the new `ETag` is persisted (provider mode only). |
| `304 Not Modified`     | Cached file is kept as-is.                                    |
| Other status / offline | Falls back to the cached file; **fails only if none exists**. |

In provider mode, a `200` response with no `ETag` header fails the build, since
change detection would otherwise be impossible.

### Usage

#### Provider (configuration-cache input)

```kotlin
// build.gradle.kts (or settings.gradle.kts)
apply(from = "script-provider.gradle.kts")

@Suppress("UNCHECKED_CAST")
val getGradleScriptDownloadProvider =
    extra["getGradleScriptDownloadProvider"]
        as (String, String, String, String, RegularFile, LogLevel) -> Provider<String>

val commonScript = layout.buildDirectory.file("gradle-scripts/common.gradle.kts").get()

val provider = getGradleScriptDownloadProvider(
    "Sonos-Inc/gradle",                 // repo (owner/name)
    "scripts/common.gradle.kts",        // path within the repo
    "main",                             // version: branch, tag, or commit SHA
    System.getenv("GITHUB_TOKEN"),      // token with read access to the repo
    commonScript,                       // local destination (RegularFile)
    LogLevel.INFO,                      // log level for progress output
)

// Resolving the provider triggers the download/cache check:
provider.get()

// The file is now downloaded/cached and can be applied:
apply(from = commonScript)
```

#### Direct download (eager)

```kotlin
apply(from = "script-provider.gradle.kts")

@Suppress("UNCHECKED_CAST")
val downloadGradleScript =
    extra["downloadGradleScript"]
        as (String, String, String, String, RegularFile, Boolean, LogLevel) -> Unit

val commonScript = layout.buildDirectory.file("gradle-scripts/common.gradle.kts").get()

downloadGradleScript(
    "Sonos-Inc/gradle",                 // repo (owner/name)
    "scripts/common.gradle.kts",        // path within the repo
    "main",                             // version: branch, tag, or commit SHA
    System.getenv("GITHUB_TOKEN"),      // token with read access to the repo
    commonScript,                       // local destination (RegularFile)
    false,                              // forceDownload: re-download even if cached
    LogLevel.INFO,                      // log level for progress output
)

apply(from = commonScript)
```

### Parameters

Both entry points share the same core parameters:

| Parameter     | Type          | Description                                                  |
| ------------- | ------------- | ----------------------------------------------------------- |
| `repo`        | `String`      | GitHub repository in `owner/name` form.                     |
| `path`        | `String`      | Path to the file within the repository.                     |
| `version`     | `String`      | Git ref to fetch at — branch, tag, or commit SHA.           |
| `githubToken` | `String`      | GitHub token (PAT or app token) with read access to `repo`. |
| `targetFile`  | `RegularFile` | Local destination; the `.etag` sidecar lives next to it.    |
| `logLevel`    | `LogLevel`    | Level at which progress is logged.                          |

`downloadGradleScript` additionally takes:

| Parameter       | Type      | Description                                                  |
| --------------- | --------- | ----------------------------------------------------------- |
| `forceDownload` | `Boolean` | Re-download even when a cached file already exists.          |

[`ValueSource`]: https://docs.gradle.org/current/userguide/configuration_cache.html#config_cache:requirements:external_processes
[`raw.githubusercontent.com`]: https://docs.github.com/en/rest/repos/contents#get-repository-content
