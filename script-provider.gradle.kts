import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.provider.Property
import org.gradle.api.file.RegularFileProperty
import java.net.HttpURLConnection
import java.net.URL

interface DownloaderParameters : ValueSourceParameters {
    val url: Property<String>
    val outputFile: RegularFileProperty
}

abstract class ETagDownloaderSource : ValueSource<String, DownloaderParameters> {
    override fun obtain(): String? {
        val urlString = parameters.url.get()
        val targetFile = parameters.outputFile.get().asFile
        
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        
        // Optional: If file exists, send ETag to server for a 304 Not Modified check
        // val existingETag = getSavedETag() 
        // if (existingETag != null) connection.setRequestProperty("If-None-Match", existingETag)

        connection.connect()

        val responseCode = connection.responseCode
        val etag = connection.getHeaderField("ETag") ?: "${System.currentTimeMillis()}" // Fallback if server lacks ETag

        if (responseCode == HttpURLConnection.HTTP_OK) {
            // Server has a new version, download and overwrite the file
            connection.inputStream.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } else if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
            // File is up to date, do nothing
        }

        connection.disconnect()
        
        // Returning the ETag tells Gradle to watch this specific string for changes
        return etag
    }
}
