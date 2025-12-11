package com.zsoltk130.http_fm

import fi.iki.elonen.NanoHTTPD
import android.content.Context
import android.os.Environment
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HTTPServer(
    private val context: Context,
    private val rootDir: File = Environment.getExternalStorageDirectory(),
    private val isPasswordProtected: Boolean,
    private val accessToken: String?,
    private val encryptionKey: ByteArray,
    private val log: (String) -> Unit
) : NanoHTTPD(8080) {
    private val authorisedClients = mutableSetOf<String>()

    // Main function that delegates functionality depending on URI
    override fun serve(session: IHTTPSession): Response {
        val clientIP = session.remoteIpAddress

        if (isPasswordProtected && !isAuthorised(session, clientIP)) {
            return servePasswordPage()
        }

        val uri = session.uri

        // Time formatting
        val time = ZonedDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val formattedTime = time.format(formatter)

        // Log accesses
        if (uri != "/favicon.ico") {
            log("[$formattedTime] Request from $clientIP to $uri")
        }

        // HTML Handlers
        return when {
            uri == "/" -> serveContentPage(rootDir)

            uri.startsWith("/browse/") -> {
                val relPath = URLDecoder.decode(uri.removePrefix("/browse/"), "UTF-8")
                val dir = File(rootDir, relPath)
                if (dir.exists() && dir.isDirectory) {
                    serveContentPage(dir, relPath)
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

            uri == "/upload" && session.method == Method.POST -> handleEncryptedUpload(session)

            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
        }
    }

    // SECURITY //
    // PASSWORD SECURITY
    // Check whether user is authorized
    private fun isAuthorised(session: IHTTPSession, clientIP: String): Boolean {
        val token = session.parms["token"]

        if (token == accessToken) {
            authorisedClients.add(clientIP)
            return true
        }

        return authorisedClients.contains(clientIP)
    }

    // Display auth page
    private fun servePasswordPage(): Response {
        val html = """
        <html>
        <head>
            <title>http-fm – Access Required</title>
            <style>
                body {
                    margin: 0;
                    height: 100vh;
                    background-color: #1e1e1e;
                    color: #c7fdbb;
                    font-family: monospace;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                }

                .container {
                    text-align: center;
                    background-color: #2e2e2e;
                    padding: 30px;
                    border-radius: 10px;
                    box-shadow: 0 0 20px rgba(0,0,0,0.5);
                }

                input {
                    width: 200px;
                    padding: 10px;
                    background: #1e1e1e;
                    border: 1px solid #78e2a0;
                    color: #c7fdbb;
                    text-align: center;
                    font-size: 16px;
                }

                button {
                    margin-top: 15px;
                    padding: 10px 25px;
                    background: #78e2a0;
                    border: none;
                    border-radius: 5px;
                    cursor: pointer;
                    font-weight: bold;
                }

                button:hover {
                    background: #9eff7c;
                }
            </style>
        </head>
        <body>
            <div class="container">
                <h2>Access Required</h2>
                <form method="GET" action="/">
                    <input type="password" name="token" placeholder="Enter access token" required />
                    <br/>
                    <button type="submit">Enter</button>
                </form>
            </div>
        </body>
        </html>
    """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    // AES ENCRYPTION
    private fun decryptAESCBC(iv: ByteArray, ciphertext: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val key = SecretKeySpec(encryptionKey, "AES")
        val ivSpec = IvParameterSpec(iv)

        cipher.init(Cipher.DECRYPT_MODE, key, ivSpec)
        val plainBytes = cipher.doFinal(ciphertext)

        return String(plainBytes, Charsets.UTF_8)
    }

    private fun handleEncryptedUpload(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        return try {
            session.parseBody(files)
            log("Upload request received")

            val rawJson = files["postData"]
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No POST data found.")
            log("Encrypted upload received")
            log("Received ${rawJson.length} bytes")

            val json = JSONObject(rawJson)
            val iv = Base64.decode(json.getString("iv"), Base64.DEFAULT)
            val data = Base64.decode(json.getString("data"), Base64.DEFAULT)

            log("Decrypting ${data.size} bytes...")
            val plaintext = decryptAESCBC(iv, data)
            log("Decrypted successfully")

            val obj = JSONObject(plaintext)
            val filename = obj.getString("filename")
            val base64Data = obj.getString("data")
            val directory = obj.getString("directory")

            val bytes = Base64.decode(base64Data, Base64.DEFAULT)

            val targetDir = File(rootDir, directory)
            if (!targetDir.exists()) targetDir.mkdirs()

            val outFile = File(targetDir, filename)
            outFile.writeBytes(bytes)

            log("Saved file: ${outFile.absolutePath} (${bytes.size} bytes)")

            newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
        } catch (e: Exception) {
            log("Upload error: ${e.message}")
            e.printStackTrace()
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Error: ${e.message}"
            )
        }
    }

    // SECURITY END //

    // Display phone contents page
    private fun serveContentPage(dir: File, relPath: String = ""): Response {
        val keyBase64 = Base64.encodeToString(encryptionKey, Base64.NO_WRAP)
        val safeKey = JSONObject.quote(keyBase64)

        val files = dir.listFiles()?.toList() ?: return newFixedLengthResponse("No files found.")
        val html = buildString {
            append(
                $"""
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
                        font-size: 4em;
                        display: block;
                        margin-bottom: 8px;
                        color: #c7fdbb;
                    }
                    button {
                        margin-top: 20px;
                        background: #78e2a0;
                        border: none;
                        padding: 10px 20px;
                        border-radius: 5px;
                        cursor: pointer;
                    }
                    button:hover {
                        background: #9eff7c;
                    }
                    .video-placeholder {
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        height: 120px;
                        background-color: #2e2e2e;
                        border-radius: 10px;
                        cursor: pointer;
                        color: #78e2a0;
                        font-size: 1.5em;
                        transition: background-color 0.3s;
                    }
                    .video-placeholder:hover {
                        background-color: #3a3a3a;
                    }
                    .video-placeholder .icon {
                        font-size: 3em;
                    }
                </style>
                
                <script src="https://cdnjs.cloudflare.com/ajax/libs/crypto-js/4.1.1/crypto-js.min.js"></script>
                <script>
                    // AES ENCRYPTION
                    const AES_KEY_B64 = $safeKey;
                    
                    function encryptWithCryptoJS(plaintext, base64Key) {
                        const key = CryptoJS.enc.Base64.parse(base64Key);
                        const iv = CryptoJS.lib.WordArray.random(16); // 16 bytes for AES-CBC
                        
                        const encrypted = CryptoJS.AES.encrypt(plaintext, key, {
                            iv: iv,
                            mode: CryptoJS.mode.CBC,
                            padding: CryptoJS.pad.Pkcs7
                        });
                        
                        return JSON.stringify({
                            iv: CryptoJS.enc.Base64.stringify(iv),
                            data: encrypted.toString()
                        });
                    }
                        
                    async function uploadFiles() {
                        const input = document.querySelector("input[type='file']");
                        const progressBar = document.getElementById("progressBar");
                        const statusDiv = document.getElementById("uploadStatus");

                        const files = input.files;
                        if (files.length === 0) return false;

                        progressBar.style.display = "block";

                        for (const file of files) {
                            try {
                                statusDiv.textContent = `Processing ${'$'}{file.name}...`;
                                
                                // Read file as base64
                                const arrayBuffer = await file.arrayBuffer();
                                const uint8Array = new Uint8Array(arrayBuffer);
                                
                                // Convert to base64 in smaller chunks to avoid memory issues
                                let base64File = '';
                                const chunkSize = 8192;
                                for (let i = 0; i < uint8Array.length; i += chunkSize) {
                                    const chunk = uint8Array.slice(i, i + chunkSize);
                                    base64File += String.fromCharCode(...chunk);
                                }
                                base64File = btoa(base64File);

                                const payloadObj = {
                                    filename: file.name,
                                    data: base64File,
                                    directory: document.querySelector("input[name='path']").value
                                };

                                statusDiv.textContent = `Encrypting ${'$'}{file.name}...`;

                                // Encrypt
                                const key = CryptoJS.enc.Base64.parse(AES_KEY_B64);
                                const iv = CryptoJS.lib.WordArray.random(16);
                                
                                const encrypted = CryptoJS.AES.encrypt(
                                    JSON.stringify(payloadObj), 
                                    key, 
                                    {
                                        iv: iv,
                                        mode: CryptoJS.mode.CBC,
                                        padding: CryptoJS.pad.Pkcs7
                                    }
                                );

                                const encryptedJSON = JSON.stringify({
                                    iv: CryptoJS.enc.Base64.stringify(iv),
                                    data: encrypted.toString()
                                });

                                statusDiv.textContent = `Uploading ${'$'}{file.name}...`;

                                const response = await fetch("/upload", {
                                    method: "POST",
                                    headers: { "Content-Type": "application/json" },
                                    body: encryptedJSON
                                });

                                if (!response.ok) {
                                    const errorText = await response.text();
                                    throw new Error(`Upload failed: ${'$'}{response.status} - ${'$'}{errorText}`);
                                }

                                statusDiv.textContent = `Uploaded ${'$'}{file.name} successfully!`;

                            } catch (error) {
                                console.error("Upload error:", error);
                                statusDiv.textContent = `Error uploading ${'$'}{file.name}: ${'$'}{error.message}`;
                                progressBar.style.display = "none";
                                return false;
                            }
                        }

                        statusDiv.textContent = "All uploads complete!";
                        setTimeout(() => location.reload(), 1500);
                        return false;
                    }
                    
                    // Lazy-load images, videos, and audio
                    document.addEventListener("DOMContentLoaded", function() {
                        const lazyMedia = document.querySelectorAll('img.lazy, video.lazy-video, audio.lazy-audio');
                        const observer = new IntersectionObserver(entries => {
                            entries.forEach(entry => {
                                if (entry.isIntersecting) {
                                    const el = entry.target;
                                    if (el.dataset.src) el.src = el.dataset.src;
                                    el.classList.remove('lazy', 'lazy-video', 'lazy-audio');
                                    observer.unobserve(el);
                                }
                            });
                        });
                        lazyMedia.forEach(el => observer.observe(el));

                        // Handle large video placeholders
                        document.querySelectorAll('.video-placeholder').forEach(placeholder => {
                            placeholder.addEventListener('click', () => {
                                const videoUrl = placeholder.dataset.src;
                                const container = document.createElement('div');
                                container.innerHTML = `
                                    <video controls autoplay style="width:100%; height:auto;">
                                        <source src="${'$'}{videoUrl}">
                                        Your browser does not support video playback.
                                    </video>`;
                                placeholder.replaceWith(container);
                            });
                        });
                    });
                </script>
            </head>
            
            <body>
                <h2>Files in /$$relPath</h2>
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
                <form id="uploadForm" onsubmit="event.preventDefault(); uploadFiles(); return false;">
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

    // Helper functions for downloading multiple files
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

    // Thumbnail generation
    private fun generatePreviewHTML(encodedPath: String, fileName: String): String {
        val previewUrl = "/preview/$encodedPath"
        val mimeType = URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"
        val file = File(rootDir, URLDecoder.decode(encodedPath, "UTF-8"))

        return when {
            mimeType.startsWith("image/") -> """
            <img data-src="$previewUrl" alt="$fileName" class="lazy" loading="lazy">
        """

            mimeType.startsWith("video/") -> {
                val sizeMB = file.length() / (1024.0 * 1024.0)
                if (sizeMB > 50) {
                    // Show a static thumbnail or placeholder instead of loading
                    """
                <div class="video-placeholder" data-src="$previewUrl" data-name="$fileName">
                    <span class="icon">&#9658;</span><br>
                    <small>${"%.1f".format(sizeMB)} MB (click to preview)</small>
                </div>
                """
                } else {
                    """
                <video class="lazy-video" data-src="$previewUrl" controls preload="none">
                    Your browser does not support the video tag.
                </video>
                """
                }
            }

            mimeType.startsWith("audio/") -> """
            <audio class="lazy-audio" data-src="$previewUrl" controls preload="none"></audio>
        """

            else -> """<span class="icon">&#128196;</span>""" // generic file icon
        }
    }
}