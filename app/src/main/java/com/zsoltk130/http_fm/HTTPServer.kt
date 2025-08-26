package com.zsoltk130.http_fm

import fi.iki.elonen.NanoHTTPD
import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HTTPServer(
    private val context: Context,
    private val rootDir: File = Environment.getExternalStorageDirectory(),
    private val log: (String) -> Unit
) : NanoHTTPD(8080) {

    override fun serve(session: IHTTPSession): Response {
        val clientIP = session.remoteIpAddress
        val uri = session.uri

        // Log accesses
        if (uri != "/favicon.ico") {
            log("Request from $clientIP to $uri")
        }

        return when {
            uri == "/" -> listContentsInDirectory(rootDir)

            uri.startsWith("/browse/") -> {
                val relPath = URLDecoder.decode(uri.removePrefix("/browse/"), "UTF-8")
                val dir = File(rootDir, relPath)
                if (dir.exists() && dir.isDirectory) {
                    listContentsInDirectory(dir, relPath)
                } else {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Directory not found.")
                }
            }

            uri.startsWith("/download/") -> {
                val relPath = URLDecoder.decode(uri.removePrefix("/download/").replace("+", "%2B"), "UTF-8")
                val file = File(rootDir, relPath)
                serveFile(file)
            }

            uri.startsWith("/preview/") -> {
                val relPath = URLDecoder.decode(uri.removePrefix("/preview/").replace("+", "%2B"), "UTF-8")
                val file = File(rootDir, relPath)
                serveRawFile(file)
            }

            uri == "/download-multiple" && session.method == Method.POST -> handleMultiDownload(session)

            uri == "/upload" && session.method == Method.POST -> handleFileUpload(session)

            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
        }
    }

    // Build HTML to display contents of the phone's storage to user
    private fun listContentsInDirectory(dir: File, relPath: String = ""): Response {
        val files = dir.listFiles()?.toList() ?: return newFixedLengthResponse("No files found.")
        val html = buildString {
            append("""
            <html>
            <head>
                <title>http-fm</title>
                <style>
                    body {
                        background-color: #1e1e1e;
                        color: #c7fdbb;
                        font-family: monospace;
                        padding: 20px;
                    }
                    h2 {
                        color: #9eff7c;
                        margin-bottom: 20px;
                    }
                    ul {
                        list-style-type: none;
                        padding: 0;
                    }
                    li {
                        margin: 10px 0;
                    }
                    a {
                        text-decoration: none;
                        color: #78e2a0;
                    }
                    a:hover {
                        text-decoration: underline;
                        color: #bbffaa;
                    }
                    .folder {
                        font-weight: bold;
                    }
                    .back {
                        color: #ffb347;
                    }
                    .grid {
                        display: grid;
                        grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
                        gap: 20px;
                        padding: 0;
                        margin-top: 20px;
                    }
                    .entry {
                        background-color: #2e2e2e;
                        border-radius: 10px;
                        padding: 10px;
                        text-align: center;
                        color: #c7fdbb;
                        overflow: hidden;
                    }
                    .entry img, .entry video, .entry audio, .entry embed {
                        width: 100%;
                        height: 100px;
                        object-fit: cover;
                        border-radius: 6px;
                        margin-bottom: 8px;
                    }
                    .entry input[type="checkbox"] {
                        margin-bottom: 6px;
                    }
                    .icon {
                        font-size: 4em; /* Adjust size as needed */
                        display: block;
                        margin-bottom: 8px;
                        color: #c7fdbb; /* Icon color */
                    }
                </style>
                <script>
                function uploadFiles() {
                    const form = document.getElementById('uploadForm');
                    const formData = new FormData(form);
                    const progressBar = document.getElementById('progressBar');
                    const statusDiv = document.getElementById('uploadStatus');

                    // Show progress bar
                    progressBar.style.display = 'block';
                    statusDiv.innerHTML = 'Uploading...';

                    const xhr = new XMLHttpRequest();

                    // Track upload progress
                    xhr.upload.addEventListener('progress', function(e) {
                        if (e.lengthComputable) {
                            const percentComplete = (e.loaded / e.total) * 100;
                            progressBar.value = percentComplete;
                            statusDiv.innerHTML = 'Uploading... ' + Math.round(percentComplete) + '%';
                        }
                    });

                    // Handle completion
                    xhr.addEventListener('load', function() {
                        if (xhr.status === 200) {
                            statusDiv.innerHTML = 'Upload successful!';
                            form.reset();
                            // Refresh the page after a short delay
                            setTimeout(() => location.reload(), 1000);
                        } else {
                            statusDiv.innerHTML = 'Upload failed!';
                        }
                        progressBar.style.display = 'none';
                    });

                    xhr.open('POST', '/upload');
                    xhr.send(formData);

                    return false; // Prevent normal form submission
                }
                </script>
            </head>
            <body>
                <h2>Files in /$relPath</h2>
                <ul>
            """.trimIndent())

            // Back link
            if (dir != rootDir) {
                val parentRelPath = File(relPath).parent ?: ""
                val encoded = URLEncoder.encode(parentRelPath, "UTF-8")
                append("<a class=\"back\" href=\"/browse/$encoded\">⬅️ .. (previous)</a>")
            }

            append("""
                <form method="POST" action="/download-multiple">
                <div class="grid">
            """.trimIndent())

            // List items
            for (file in files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))) {
                val name = file.name
                val path = if (relPath.isBlank()) name else "$relPath/$name"
                val encodedPath = URLEncoder.encode(path, "UTF-8")
                // Use generatePreviewHTML for files, and the folder icon for directories
                val displayElement = if (file.isDirectory) {
                    "<span class=\"icon\">&#128193;</span>" // Folder icon
                } else {
                    generatePreviewHTML(encodedPath, name) // Preview or file icon
                }
                val href = if (file.isDirectory) "/browse/$encodedPath" else "/download/$encodedPath"

                append("""
                <div class="entry">
                  <input type="checkbox" name="selected" value="$encodedPath" />
                  <div>
                    $displayElement
                  </div>
                  <a href="$href">$name</a>
                </div>
                """.trimIndent())
            }

            append("""
                </div>
                <button type="submit">Download Selected</button>
                </form>
            """.trimIndent())

            append("""
                <form id="uploadForm" onsubmit="return uploadFiles()">
                    <input type="file" name="file" multiple />
                    <input type="hidden" name="path" value="$relPath" />
                    <button type="submit">Upload</button>
                    <progress id="progressBar" value="0" max="100" style="display:none; width: 100%; margin-top: 10px;"></progress>
                    <div id="uploadStatus"></div>
                </form>
            """.trimIndent())
        }

        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    // File download
    private fun serveFile(file: File): Response {
        return if (file.exists() && file.isFile) {
            val mime = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
            val inputStream = FileInputStream(file)
            val response = newChunkedResponse(Response.Status.OK, mime, inputStream)

            // Add content disposition header to set filename correctly
            response.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")

            response
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found.")
        }
    }

    // File preview
    private fun serveRawFile(file: File): Response {
        return if (file.exists() && file.isFile) {
            val mime = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
            val inputStream = FileInputStream(file)
            newChunkedResponse(Response.Status.OK, mime, inputStream)
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found.")
        }
    }

    private fun addFileToZip(file: File, zipPath: String, zipOut: ZipOutputStream) {
        zipOut.putNextEntry(ZipEntry(zipPath))
        file.inputStream().use { it.copyTo(zipOut) }
        zipOut.closeEntry()
    }

    private fun addDirectoryToZip(dir: File, basePath: String, zipOut: ZipOutputStream) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            val path = "$basePath/${file.name}"
            if (file.isDirectory) {
                addDirectoryToZip(file, path, zipOut)
            } else {
                addFileToZip(file, path, zipOut)
            }
        }
    }

    // Multi-file download
    private fun handleMultiDownload(session: IHTTPSession): Response {
        session.parseBody(mutableMapOf()) // Parse POST data

        val selected = session.parameters["selected"]
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No files selected.")

        if (selected.size == 1) {
            // Only one item selected — decode and serve directly
            val decoded = URLDecoder.decode(selected[0], "UTF-8")
            val file = File(rootDir, decoded)

            return if (file.exists() && file.isFile) {
                serveFile(file)
            } else if (file.exists() && file.isDirectory) {
                // If it's a single directory, still ZIP it
                val zipFile = File.createTempFile("download_", ".zip", context.cacheDir)
                ZipOutputStream(zipFile.outputStream()).use { zipOut ->
                    addDirectoryToZip(file, file.name, zipOut)
                }

                val response = newChunkedResponse(
                    Response.Status.OK,
                    "application/zip",
                    FileInputStream(zipFile)
                )
                response.addHeader("Content-Disposition", "attachment; filename=\"${file.name}.zip\"")
                response
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found.")
            }
        }

        // Otherwise, multiple files/folders selected — ZIP them all
        val zipFile = File.createTempFile("download_", ".zip", context.cacheDir)
        ZipOutputStream(zipFile.outputStream()).use { zipOut ->
            for (relPath in selected) {
                val decoded = URLDecoder.decode(relPath, "UTF-8")
                val file = File(rootDir, decoded)
                if (file.exists()) {
                    if (file.isFile) {
                        addFileToZip(file, decoded, zipOut)
                    } else if (file.isDirectory) {
                        addDirectoryToZip(file, decoded, zipOut)
                    }
                }
            }
        }

        val response = newChunkedResponse(
            Response.Status.OK,
            "application/zip",
            FileInputStream(zipFile)
        )
        response.addHeader("Content-Disposition", "attachment; filename=\"files.zip\"")
        return response
    }

    // File upload
    private fun handleFileUpload(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()

        try {
            session.parseBody(files)
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Upload failed: ${e.message}")
        }

        val targetRelPath = session.parameters["path"]?.firstOrNull() ?: ""
        val uploadDir = File(rootDir, targetRelPath)
        if(!uploadDir.exists()) uploadDir.mkdirs()

        var success = false

        for ((formName, tempFilePath) in files) {
            if (formName.startsWith("file")) {
                val fileParamName = formName
                val originalName = session.parameters[fileParamName]?.firstOrNull() ?: continue
                val decodedName = URLDecoder.decode(originalName, "UTF-8")
                val targetFile = File(uploadDir, decodedName)

                val tempFile = File(tempFilePath)
                if (tempFile.exists()) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                    success = true
                }
            }
        }

        return if (success) {
            newFixedLengthResponse(Response.Status.OK, "text/html", """
            <html><body>
                <p>File(s) uploaded successfully.</p>
                <a href="/browse/${URLEncoder.encode(targetRelPath, "UTF-8")}">Go back</a>
            </body></html>
            """.trimIndent())
        } else {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No files uploaded.")
        }
    }

    private fun generatePreviewHTML(encodedPath: String, fileName: String): String {
        val previewUrl = "/preview/$encodedPath"
        val mimeType = URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"

        return when {
            mimeType.startsWith("image/") -> """<img src="$previewUrl" alt="$fileName">"""
            mimeType.startsWith("video/") -> """<video controls><source src="$previewUrl" type="$mimeType">Your browser does not support the video tag.</video>"""
            mimeType.startsWith("audio/") -> """<audio controls><source src="$previewUrl" type="$mimeType">Your browser does not support the audio tag.</audio>"""
            // Add a generic file icon for other file types
            else -> """<span class="icon">&#128196;</span>""" // Unicode for file icon
        }
    }
}
