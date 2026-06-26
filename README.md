# Gradle Script Download Provider

[`script-provider.gradle.kts`](script-provider.gradle.kts) downloads Gradle
script files from a **private GitHub repository** at configuration time and
caches them on disk, so shared build logic can be versioned in one repo and
pulled into many projects without vendoring it.

It is built around a Gradle [`ValueSource`] Provider, which makes the download a
first-class **configuration-cache input**: the build re-runs the dependent
logic only when the remote file actually changes.

### How it works

1. The file is fetched through the [GitHub Contents API] at a specific ref
   (branch, tag, or commit SHA), requesting the raw bytes.
2. The response `ETag` is stored in a sidecar file next to the download
   (`<targetFile>.etag`).
3. On subsequent runs the saved `ETag` is replayed via the `If-None-Match`
   header. An unchanged ref returns `304 Not Modified` and the cached file is
   reused — no re-download.
4. The `ETag` is returned from the `ValueSource`, so Gradle invalidates the
   configuration cache (and re-applies the downloaded script) only when it
   changes.

### Caching & offline behavior

| Remote response        | Behavior                                                      |
| ---------------------- | ------------------------------------------------------------ |
| `200 OK`               | File is (over)written and the new `ETag` is persisted.       |
| `304 Not Modified`     | Cached file is kept as-is.                                    |
| Other status / offline | Falls back to the cached file; **fails only if none exists**. |

A `200` response with no `ETag` header fails the build, since change
detection would otherwise be impossible.

### Usage

Apply the script and call the `downloadGradleScript` helper it registers on
`extra`:

```kotlin
// build.gradle.kts (or settings.gradle.kts)
apply(from = "script-provider.gradle.kts")

@Suppress("UNCHECKED_CAST")
val downloadGradleScript =
    extra["downloadGradleScript"] as (String, String, String, String, RegularFile, LogLevel) -> Unit

val commonScript = layout.buildDirectory.file("gradle-scripts/common.gradle.kts").get()

downloadGradleScript(
    "Sonos-Inc/gradle",                 // repo (owner/name)
    "scripts/common.gradle.kts",        // path within the repo
    "main",                             // version: branch, tag, or commit SHA
    System.getenv("GITHUB_TOKEN"),      // token with read access to the repo
    commonScript,                       // local destination (RegularFile)
    LogLevel.INFO,                      // log level for progress output
)

// The file is now downloaded/cached and can be applied:
apply(from = commonScript)
```

### Parameters

| Parameter     | Type          | Description                                                  |
| ------------- | ------------- | ----------------------------------------------------------- |
| `repo`        | `String`      | GitHub repository in `owner/name` form.                     |
| `path`        | `String`      | Path to the file within the repository.                     |
| `version`     | `String`      | Git ref to fetch at — branch, tag, or commit SHA.           |
| `githubToken` | `String`      | GitHub token (PAT or app token) with read access to `repo`. |
| `targetFile`  | `RegularFile` | Local destination; the `.etag` sidecar lives next to it.    |
| `logLevel`    | `LogLevel`    | Level at which progress is logged.                          |

[`ValueSource`]: https://docs.gradle.org/current/userguide/configuration_cache.html#config_cache:requirements:external_processes
[GitHub Contents API]: https://docs.github.com/en/rest/repos/contents#get-repository-content
