import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.provider.Property
import org.gradle.api.file.RegularFileProperty
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * A Gradle [ValueSource] provider that downloads a single file from a private GitHub
 * repository via the GitHub Contents API and caches it on disk.
 *
 * Why a ValueSource? Running the download here (rather than in a task or
 * directly in the build script) makes it a first-class configuration-cache
 * input. Gradle invokes [obtain] whenever the configuration cache is missing
 * or potentially stale, and treats the returned value as the cached input.
 * By returning the response ETag, we tell Gradle to re-run the build logic
 * that depends on this source only when the remote file actually changes.
 *
 * Caching strategy:
 *  - The downloaded file is written to [Params.targetFile]; the matching ETag
 *    is persisted alongside it in a `<targetFile>.etag` sidecar file.
 *  - On each run the saved ETag is replayed via the `If-None-Match` header so
 *    an unchanged ref returns HTTP 304 and no re-download occurs.
 *  - If the remote is unreachable or returns a non-200/304 status, a
 *    previously cached file is reused; we only fail when no cache exists.
 *
 * Use the `downloadGradleScript` helper (registered on `extra` below) instead
 * of wiring this source up by hand.
 */
abstract class GradleScriptSource : ValueSource<String, GradleScriptSource.Params> {
    interface Params : ValueSourceParameters {
        /** GitHub repository in `owner/name` form, e.g. `Sonos-Inc/gradle`. */
        val repo: Property<String>
        /** Path to the file within the repository, e.g. `scripts/common.gradle.kts`. */
        val path: Property<String>
        /** Git ref (branch, tag, or commit SHA) to fetch the file at. */
        val version: Property<String>
        /** GitHub token (PAT or app token) with read access to [repo]. */
        val githubToken: Property<String>
        /** Local destination for the downloaded file; the ETag sidecar lives next to it. */
        val targetFile: RegularFileProperty

        // optional
        /** Level at which progress is logged. Defaults to INFO when unset. */
        val logLevel: Property<LogLevel>
    }

    override fun obtain(): String? {
        return downloadGradleScript(
            // A fresh logger per call: ValueSource instances are not guaranteed to
            // be reused, and Logging.getLogger is cheap.
            logger = Logging.getLogger(GradleScriptSource::class.java),
            logLevel = parameters.logLevel.orElse(LogLevel.INFO).get(),
            repo = parameters.repo.get(),
            path = parameters.path.get(),
            version = parameters.version.get(),
            githubToken = parameters.githubToken.get(),
            targetFile = parameters.targetFile.get().asFile,
            checkRemote = true,
        )
    }

    companion object {
        /**
         * The actual function that does the downloading
         *
         * Use the `downloadGradleScript` helper (registered on `extra` below) instead
         * of trying to call this directly.
         */
        fun downloadGradleScript(logger: Logger,
                                 logLevel: LogLevel,
                                 repo: String,
                                 path: String,
                                 version: String,
                                 githubToken: String,
                                 targetFile: File,
                                 checkRemote: Boolean,
                                 forceDownload: Boolean = false): String {
            // Using the api.github.com API is the preferred method, however, it's THREE times slower!
            // TODO: Look into whether we want to go back to the api.github.com url
            //       To test it, run `curl -H "Authorization: Bearer $GITHUB_TOKEN" \
            //                             -H "Accept: application/vnd.github.raw" \
            //                             -H "If-None-Match: \"<etag>\"" \
            //                             -o /dev/null -w "%{http_code} %{time_total}\n" \
            //                             <url>
            //val url = "https://api.github.com/repos/$repo/contents/$path?ref=$version"
            val url = "https://raw.githubusercontent.com/$repo/$version/$path"
            // Sidecar file holding the ETag of the currently cached download.
            val etagFile = File(targetFile.parentFile, "${targetFile.name}.etag")

            val builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer $githubToken")
                // Ask the Contents API for the raw file bytes rather than the
                // default JSON metadata envelope.
                .header("Accept", "application/vnd.github.raw")
                .GET()

            var etag = ""
            // Replay the saved ETag so an unchanged ref comes back as 304.
            if (checkRemote && targetFile.exists() && etagFile.exists()) {
                etag = etagFile.readText()
                builder.header("If-None-Match", etag)
            }

            if (checkRemote || !targetFile.exists() || forceDownload) {
                try {
                    logger.log(logLevel, "Downloading $url")
                    val response = HttpClient.newHttpClient()
                        .send(builder.build(), HttpResponse.BodyHandlers.ofString())

                    when (response.statusCode()) {
                        // New content: overwrite the target and persist the new ETag.
                        200 -> {
                            targetFile.parentFile?.mkdirs()
                            targetFile.writeText(response.body())
                            if (checkRemote) {
                                response.headers().firstValue("ETag")
                                    .ifPresentOrElse({
                                        etag = it
                                        etagFile.writeText(etag)
                                    }, {
                                        // Without an ETag we cannot do conditional requests,
                                        // which would break change detection — fail loudly.
                                        throw GradleException("No ETag returned when downloading $path@$version from $repo")
                                    })
                            }
                        }

                        304 -> Unit // Cache is current.
                        // Any other status: prefer a stale cache over failing the build,
                        // but fail if we have nothing cached to fall back on.
                        else -> if (!targetFile.exists()) {
                            throw GradleException("Failed to download $path@$version from $repo (HTTP ${response.statusCode()})")
                        } else {
                            logger.warn("WARNING: $path@$version from $repo returned HTTP ${response.statusCode()}; using cached $targetFile")
                        }
                    }
                } catch (e: Throwable) {
                    // Offline / unreachable: fall back to the cache if we have one.
                    if (!targetFile.exists()) throw e
                }
            }

            // Returning the ETag tells Gradle to watch this specific string for changes
            return etag
        }
    }
}

/**
 * Convenience entry point, exposed via `extra` so it can be called from any
 * build script that applies this file:
 *
 *     val downloadGradleScript: (String, String, String, String, RegularFile, LogLevel) -> Unit by extra
 *     downloadGradleScript("Sonos-Inc/gradle", "scripts/common.gradle.kts", "main", token, targetFile, LogLevel.INFO)
 *
 * Downloads `path` at `version` from `repo` into `targetFile`, reusing the
 * on-disk cache when the remote ref is unchanged. See [GradleScriptSource]
 * for the caching details.
 */
extra["downloadGradleScript"] = fun(repo: String,
                                    path: String,
                                    version: String,
                                    githubToken: String,
                                    targetFile: RegularFile,
                                    checkRemote: Boolean,
                                    forceDownload: Boolean,
                                    logLevel: LogLevel) {
    // if checkRemote is true, then we want to use our GradleScriptSource which
    // will cause the Configuration phase to run again if the remote file changes.
    if (checkRemote) {
        // Calling .get() records the fetched content as a configuration-cache input,
        // so obtain() re-runs every build and a changed remote ref invalidates the
        // cache (and re-runs this body's apply) without a manual version bump.
        providers.of(GradleScriptSource::class.java) {
            parameters.repo.set(repo)
            parameters.path.set(path)
            parameters.version.set(version)
            parameters.githubToken.set(githubToken)
            parameters.targetFile.set(targetFile)
            parameters.logLevel.set(logLevel)
        }.get()
    } else {
        GradleScriptSource.downloadGradleScript(
            logger = logger,
            logLevel = logLevel,
            repo = repo,
            path = path,
            version = version,
            githubToken = githubToken,
            targetFile = targetFile.asFile,
            checkRemote = checkRemote,
            forceDownload = forceDownload,
        )
    }
}
