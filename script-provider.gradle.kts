@file:Suppress("UNCHECKED_CAST")

import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.provider.Property
import org.gradle.api.file.RegularFileProperty
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

interface DownloaderParameters : ValueSourceParameters {
    val repo: Property<String>
    val path: Property<String>
    val version: Property<String>
    val githubToken: Property<String>
    val targetFile: RegularFileProperty

    // optional
    val logLevel: Property<LogLevel>
}

abstract class GradleScriptSource : ValueSource<String, DownloaderParameters> {
    override fun obtain(): String? {
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

fun <T> createParameterProvider(paramName: String, properties: Map<String, Any>) = providers.provider {
    val param = properties[paramName] as () -> T
    param()
}

fun createScriptProvider(properties: Map<String, Any>) = providers.of(GradleScriptSource::class.java) {
    parameters.repo.set(createParameterProvider<String>("repo", properties))
    parameters.path.set(createParameterProvider<String>("path", properties))
    parameters.version.set(createParameterProvider<String>("version", properties))
    parameters.githubToken.set(createParameterProvider<String>("githubToken", properties))
    parameters.targetFile.set(createParameterProvider<RegularFile>("targetFile", properties))
    parameters.logLevel.set(createParameterProvider<LogLevel>("logLevel", properties))
}

// Params needed for the providers
extra["downloadGradleScriptProviderParams"] = mutableMapOf<Provider<*>, MutableMap<String, Any>>()

// create 5 providers, which should be more than enough for all scripts needed to be downloaded
extra["downloadGradleScriptProviders"] = ArrayDeque(List(5) {
    logger.warn("Creating the provider")
    val providerParams = extra["downloadGradleScriptProviderParams"] as MutableMap<Provider<*>, MutableMap<String, Any>>
    val paramMap = mutableMapOf<String, Any>()
    val provider = createScriptProvider(paramMap)
    providerParams[provider] = paramMap
    provider
})

extra["downloadGradleScript"] = fun(repo: String,
                                    path: String,
                                    version: String,
                                    githubToken: String,
                                    targetFile: RegularFile,
                                    logLevel: LogLevel) {
    logger.warn("Getting the provider")
    val providers = extra["downloadGradleScriptProviders"] as ArrayDeque<Provider<*>>
    val provider = providers.removeFirstOrNull()
    provider?.let {
        val providerParams = extra["downloadGradleScriptProviderParams"] as MutableMap<Provider<*>, MutableMap<String, Any>>
        val params = providerParams[it]!!
        params["repo"] = { repo }
        params["path"] = { path }
        params["version"] = { version }
        params["githubToken"] = { githubToken }
        params["targetFile"] = { targetFile }
        params["logLevel"] = { logLevel }

        // CRITICAL: Calling .get() triggers the download and registers the ETag as a configuration input
        it.get()
    }
}
