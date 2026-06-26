@file:Suppress("UNCHECKED_CAST")

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.provider.Property
import org.gradle.api.file.RegularFileProperty
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

abstract class GradleScriptSource : ValueSource<String, GradleScriptSource.Params> {
    interface Params : ValueSourceParameters {
        val repo: Property<String>
        val path: Property<String>
        val version: Property<String>
        val githubToken: Property<String>
        val targetFile: RegularFileProperty

        // optional
        val logLevel: Property<LogLevel>
    }

    override fun obtain(): String? {
        val logger = Logging.getLogger(GradleScriptSource::class.java)
        val logLevel = parameters.logLevel.orElse(LogLevel.INFO).get()

        val repo = parameters.repo.get()
        val path = parameters.path.get()
        val version = parameters.version.get()
        val githubToken = parameters.githubToken.get()
        val targetFile = parameters.targetFile.get().asFile

        val url = "https://api.github.com/repos/$repo/contents/$path?ref=$version"
        val etagFile = File(targetFile.parentFile, "${targetFile.name}.etag")

        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $githubToken")
            .header("Accept", "application/vnd.github.raw")
            .GET()

        var etag = ""
        // Replay the saved ETag so an unchanged ref comes back as 304.
        if (targetFile.exists() && etagFile.exists()) {
            etag = etagFile.readText()
            builder.header("If-None-Match", etag)
        }

        try {
            logger.log(logLevel, "Downloading $url")
            val response = HttpClient.newHttpClient()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString())

            when (response.statusCode()) {
                200 -> {
                    targetFile.parentFile?.mkdirs()
                    targetFile.writeText(response.body())
                    response.headers().firstValue("ETag")
                        .ifPresentOrElse({
                            etag = it
                            etagFile.writeText(etag)
                        }, {
                            throw GradleException("No ETag returned when downloading $path@$version from $repo")
                        })
                }
                304 -> Unit // Cache is current.
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

        // Returning the ETag tells Gradle to watch this specific string for changes
        return etag
    }
}

extra["downloadGradleScript"] = fun(repo: String,
                                    path: String,
                                    version: String,
                                    githubToken: String,
                                    targetFile: RegularFile,
                                    logLevel: LogLevel) {
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
}
