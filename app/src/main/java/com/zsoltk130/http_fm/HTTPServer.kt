package com.zsoltk130.http_fm

import fi.iki.elonen.NanoHTTPD
import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection
import java.net.URLDecoder
import java.net.URLEncoder

class HTTPServer(
    private val context: Context,
    private val rootDir: File = Environment.getExternalStorageDirectory()
) : NanoHTTPD(8080) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return when {
            uri == "/" -> listFilesInDirectory(rootDir)
            uri.startsWith("/browse/") -> {
                val relPath = URLDecoder.decode(uri.removePrefix("/browse/"), "UTF-8")
                val dir = File(rootDir, relPath)
                if (dir.exists() && dir.isDirectory) {
                    listFilesInDirectory(dir, relPath)
                } else {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Directory not found.")
                }
            }
            uri.startsWith("/download/") -> {
                val relPath = URLDecoder.decode(uri.removePrefix("/download/"), "UTF-8")
                val file = File(rootDir, relPath)
                serveFile(file)
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
        }
    }

    private fun listFilesInDirectory(dir: File, relPath: String = ""): Response {
        val files = dir.listFiles()?.toList() ?: return newFixedLengthResponse("No files found.")
        val html = buildString {
            append("<html><body>")
            append("<h2>Files in /${relPath.ifBlank { "" }}</h2><ul>")

            // Back link
            if (dir != rootDir) {
                val parentRelPath = File(relPath).parent ?: ""
                val encoded = URLEncoder.encode(parentRelPath, "UTF-8")
                append("<li><a href=\"/browse/$encoded\">.. (parent)</a></li>")
            }

            for (file in files) {
                val name = file.name
                val path = if (relPath.isBlank()) name else "$relPath/$name"
                val encodedPath = URLEncoder.encode(path, "UTF-8")
                val display = if (file.isDirectory) "[DIR] $name" else name
                val href = if (file.isDirectory) "/browse/$encodedPath" else "/download/$encodedPath"
                append("<li><a href=\"$href\">$display</a></li>")
            }

            append("</ul></body></html>")
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun serveFile(file: File): Response {
        return if (file.exists() && file.isFile) {
            val mime = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
            newChunkedResponse(Response.Status.OK, mime, FileInputStream(file))
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found.")
        }
    }
}
