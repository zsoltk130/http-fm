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
    private val rootDir: File = Environment.getExternalStorageDirectory()
) : NanoHTTPD(8080) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri

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

            uri == "/download-multiple" && session.method == Method.POST -> handleMultiDownload(session)

            uri == "/upload" && session.method == Method.POST -> handleFileUpload(session)

            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
        }
    }

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
                </style>
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
                <ul>
            """.trimIndent())

        // List items
        for (file in files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))) {
            val name = file.name
            val path = if (relPath.isBlank()) name else "$relPath/$name"
            val encodedPath = URLEncoder.encode(path, "UTF-8")
            val icon = if (file.isDirectory) "📁" else "📄"

            if (file.isDirectory) {
                append("<li><a href=\"/browse/$encodedPath\">$icon $name</a></li>")
            } else {
                append("""
                    <li>
                        <input type="checkbox" name="selected" value="$encodedPath" />
                        <a href="/download/$encodedPath">$icon $name</a>
                    </li>
                """.trimIndent())
            }
        }

            append("""
                </ul>
                    <button type="submit">Download Selected</button>
                </form>
                </body>
                </html>
            """.trimIndent())

            append("""
                <form method="POST" action="/upload" enctype="multipart/form-data">
                    <input type="file" name="file" multiple />
                    <input type="hidden" name="path" value="$relPath" />
                    <button type="submit">Upload</button>
                </form>
            """.trimIndent())
        }

        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

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

    private fun handleMultiDownload(session: IHTTPSession): Response {
        session.parseBody(mutableMapOf()) // Needed to parse POST data

        val selected = session.parameters["selected"]
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No files selected.")

        val zipFile = File.createTempFile("download_", ".zip", context.cacheDir)
        ZipOutputStream(zipFile.outputStream()).use { zipOut ->
            for (relPath in selected) {
                val decoded = URLDecoder.decode(relPath, "UTF-8")
                val file = File(rootDir, decoded)
                if (file.exists() && file.isFile) {
                    zipOut.putNextEntry(ZipEntry(decoded))
                    file.inputStream().use { it.copyTo(zipOut) }
                    zipOut.closeEntry()
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

}
