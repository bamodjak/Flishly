package com.flishly.server

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

class BridgeHttpServer(
    context: Context,
    host: String?,
    port: Int
) : NanoHTTPD(host, port) {

    private val appContext: Context = context.applicationContext
    private val rootDir: File = Environment.getExternalStorageDirectory()
    private val authStore = AuthStore(appContext)
    private val rootCanonical: String = rootDir.canonicalPath

    companion object {
        const val MAX_UPLOAD_BYTES: Long = 2L * 1024 * 1024 * 1024
        const val MAX_TEXT_BYTES: Int = 2_000_000
        private const val MAX_FAILED_ATTEMPTS = 10
        private const val LOCKOUT_MS: Long = 60_000
    }

    private var failedAttempts = 0
    private var lockoutUntil = 0L

    private fun isLockedOut(): Boolean = System.currentTimeMillis() < lockoutUntil

    private fun recordAuthResult(success: Boolean) {
        synchronized(this) {
            if (success) {
                failedAttempts = 0
                lockoutUntil = 0
            } else {
                failedAttempts++
                if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                    lockoutUntil = System.currentTimeMillis() + LOCKOUT_MS
                    failedAttempts = 0
                }
            }
        }
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        if (!authStore.hasPassword()) return true
        if (isLockedOut()) return false
        val header = session.headers["authorization"] ?: return false
        if (!header.startsWith("Basic ")) return false
        return try {
            val decoded = String(Base64.decode(header.substring(6), Base64.DEFAULT))
            val parts = decoded.split(":", limit = 2)
            if (parts.size != 2) {
                recordAuthResult(false)
                false
            } else {
                val ok = authStore.verify(parts[1])
                recordAuthResult(ok)
                ok
            }
        } catch (e: Exception) {
            recordAuthResult(false)
            false
        }
    }

    private fun unauthorizedResponse(): Response {
        val r = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Authentication required")
        r.addHeader("WWW-Authenticate", "Basic realm=\"Flishly\"")
        return r
    }

    private fun sameOrigin(session: IHTTPSession): Boolean {
        val host = session.headers["host"] ?: return true
        val origin = session.headers["origin"] ?: return true
        return try {
            val uri = java.net.URI(origin)
            val originAuthority = buildString {
                append(uri.host?.lowercase() ?: return false)
                if (uri.port > 0) append(":").append(uri.port)
            }
            val hostOnly = host.substringBefore(":").lowercase()
            val hostPort = host.lowercase()
            originAuthority == hostPort || (uri.port == -1 && originAuthority == hostOnly)
        } catch (e: Exception) {
            false
        }
    }

    private fun resolvePath(raw: String?): File {
        val clean = (raw ?: "/").let { if (it.isEmpty()) "/" else it }
        val target = File(rootDir, clean.removePrefix("/"))
        val canonicalTarget = target.canonicalPath
        if (!PathGuard.isInside(rootCanonical, canonicalTarget)) {
            throw SecurityException("Path escapes root")
        }
        return File(canonicalTarget)
    }

    private fun json(obj: Any): Response {
        val r = newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString())
        return r
    }

    private fun errorJson(status: Response.Status, message: String): Response {
        val o = JSONObject()
        o.put("error", message)
        return newFixedLengthResponse(status, "application/json", o.toString())
    }

    private fun mimeFor(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "html" -> "text/html"
            "js" -> "application/javascript"
            "css" -> "text/css"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            else -> "application/octet-stream"
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        if (uri == "/" || uri == "/index.html") return serveAsset("web/index.html", "text/html")
        if (uri == "/app.js") return serveAsset("web/app.js", "application/javascript")
        if (uri == "/styles.css") return serveAsset("web/styles.css", "text/css")

        if (session.method == Method.OPTIONS) {
            val r = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            r.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
            r.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
            return r
        }

        if (!isAuthorized(session)) return unauthorizedResponse()

        val stateChanging = session.method != Method.GET
        if (stateChanging && !sameOrigin(session)) {
            return errorJson(Response.Status.FORBIDDEN, "Cross-origin request rejected")
        }

        return try {
            when {
                uri == "/api/info" && session.method == Method.GET -> handleInfo()
                uri == "/api/ls" && session.method == Method.GET -> handleLs(session)
                uri == "/api/fs" && session.method == Method.GET -> handleDownload(session)
                uri == "/api/read" && session.method == Method.GET -> handleReadText(session)
                uri == "/api/write" && session.method == Method.POST -> handleWriteText(session)
                uri == "/api/upload" && session.method == Method.POST -> handleUpload(session)
                uri == "/api/mkdir" && session.method == Method.POST -> handleMkdir(session)
                uri == "/api/delete" && session.method == Method.POST -> handleDelete(session)
                uri == "/api/move" && session.method == Method.POST -> handleMove(session)
                uri == "/api/rename" && session.method == Method.POST -> handleRename(session)
                uri == "/api/clipboard" && session.method == Method.GET -> handleClipboardGet()
                uri == "/api/clipboard" && session.method == Method.POST -> handleClipboardSet(session)
                else -> errorJson(Response.Status.NOT_FOUND, "Not found")
            }
        } catch (e: SecurityException) {
            errorJson(Response.Status.FORBIDDEN, "Access denied")
        } catch (e: Exception) {
            errorJson(Response.Status.INTERNAL_ERROR, "Server error")
        }
    }

    private fun serveAsset(path: String, mime: String): Response {
        val stream = appContext.assets.open(path)
        return newChunkedResponse(Response.Status.OK, mime, stream)
    }

    private fun bodyAsJson(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return JSONObject(files["postData"] ?: "{}")
    }

    private fun handleInfo(): Response {
        val o = JSONObject()
        o.put("brand", android.os.Build.BRAND)
        o.put("model", android.os.Build.MODEL)
        o.put("sdk", android.os.Build.VERSION.SDK_INT)
        val stat = StatFs(rootDir.path)
        val storage = JSONObject()
        storage.put("path", rootDir.path)
        storage.put("totalBytes", stat.totalBytes)
        storage.put("availableBytes", stat.availableBytes)
        o.put("storage", storage)
        return json(o)
    }

    private fun fileToJson(f: File): JSONObject {
        val o = JSONObject()
        o.put("name", f.name)
        o.put("isDir", f.isDirectory)
        o.put("size", if (f.isDirectory) 0 else f.length())
        o.put("lastModified", f.lastModified())
        o.put("isReadable", f.canRead())
        o.put("isWritable", f.canWrite())
        return o
    }

    private fun handleLs(session: IHTTPSession): Response {
        val path = session.parameters["path"]?.firstOrNull() ?: "/"
        val target = resolvePath(path)
        if (!target.exists()) return errorJson(Response.Status.NOT_FOUND, "Path not found")
        val result = JSONObject()
        result.put("path", path)
        if (target.isDirectory) {
            val arr = JSONArray()
            val children = target.listFiles() ?: emptyArray()
            children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .forEach { arr.put(fileToJson(it)) }
            result.put("isDir", true)
            result.put("content", arr)
        } else {
            result.put("isDir", false)
            result.put("size", target.length())
        }
        return json(result)
    }

    private fun parseRange(rangeHeader: String, length: Long): Pair<Long, Long>? {
        if (!rangeHeader.startsWith("bytes=")) return null
        val spec = rangeHeader.substring(6).split(",").firstOrNull()?.trim() ?: return null
        val parts = spec.split("-")
        if (parts.isEmpty()) return null
        val start = parts[0].toLongOrNull() ?: return null
        val end = if (parts.size > 1 && parts[1].isNotEmpty()) {
            parts[1].toLongOrNull() ?: return null
        } else {
            length - 1
        }
        if (start < 0 || end < start || start >= length) return null
        return Pair(start, minOf(end, length - 1))
    }

    private fun handleDownload(session: IHTTPSession): Response {
        val path = session.parameters["path"]?.firstOrNull() ?: return errorJson(Response.Status.BAD_REQUEST, "path required")
        val target = resolvePath(path)
        if (!target.exists() || target.isDirectory) return errorJson(Response.Status.NOT_FOUND, "File not found")
        val rangeHeader = session.headers["range"]
        val length = target.length()
        val mime = mimeFor(target.name)
        val safeName = target.name.replace("\"", "_")
        if (rangeHeader != null) {
            val range = parseRange(rangeHeader, length)
                ?: return errorJson(Response.Status.RANGE_NOT_SATISFIABLE, "Invalid range")
            val (start, end) = range
            val chunkLen = end - start + 1
            val raf = RandomAccessFile(target, "r")
            raf.seek(start)
            val stream = FileInputStream(raf.fd)
            val r = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, stream, chunkLen)
            r.addHeader("Content-Range", "bytes $start-$end/$length")
            r.addHeader("Accept-Ranges", "bytes")
            r.addHeader("Content-Disposition", "inline; filename=\"$safeName\"")
            return r
        }
        val stream = FileInputStream(target)
        val r = newFixedLengthResponse(Response.Status.OK, mime, stream, length)
        r.addHeader("Content-Disposition", "attachment; filename=\"$safeName\"")
        r.addHeader("Accept-Ranges", "bytes")
        return r
    }

    private fun handleReadText(session: IHTTPSession): Response {
        val path = session.parameters["path"]?.firstOrNull() ?: return errorJson(Response.Status.BAD_REQUEST, "path required")
        val target = resolvePath(path)
        if (!target.exists() || target.isDirectory) return errorJson(Response.Status.NOT_FOUND, "File not found")
        if (target.length() > MAX_TEXT_BYTES) return errorJson(Response.Status.BAD_REQUEST, "File too large to view as text")
        return json(JSONObject().put("content", target.readText()))
    }

    private fun resolveWritableDir(raw: String?): File {
        val target = resolvePath(raw)
        if (PathGuard.isRoot(rootCanonical, target.canonicalPath)) {
            throw SecurityException("Operation not permitted at storage root")
        }
        return target
    }

    private fun handleWriteText(session: IHTTPSession): Response {
        val body = bodyAsJson(session)
        val target = resolveWritableDir(body.getString("path"))
        target.writeText(body.optString("content", ""))
        return json(JSONObject().put("ok", true))
    }

    private fun handleUpload(session: IHTTPSession): Response {
        val dirPath = session.parameters["path"]?.firstOrNull() ?: "/"
        val targetDir = resolveWritableDir(dirPath)
        if (!targetDir.exists()) targetDir.mkdirs()
        val files = HashMap<String, String>()
        session.parseBody(files)
        val stat = StatFs(targetDir.path)
        val freeBytes = stat.availableBytes
        var totalIncoming = 0L
        val uploadedNames = JSONArray()
        for ((fieldName, tmpPath) in files) {
            if (fieldName == "postData") continue
            val tmp = File(tmpPath)
            val originalName = session.parameters[fieldName]?.firstOrNull() ?: tmp.name
            val safeName = PathGuard.safeFileName(originalName)
                ?: return errorJson(Response.Status.BAD_REQUEST, "Unsafe file name")
            totalIncoming += tmp.length()
            if (totalIncoming > MAX_UPLOAD_BYTES || totalIncoming > freeBytes) {
                tmp.delete()
                return errorJson(Response.Status.PAYLOAD_TOO_LARGE, "Upload exceeds size or free-space limit")
            }
            val dest = File(targetDir, safeName)
            if (dest.exists()) {
                val base = dest.nameWithoutExtension
                val ext = dest.extension
                var candidate = File(targetDir, "$base (1).$ext")
                var n = 1
                while (candidate.exists() && n < 1000) {
                    n++
                    candidate = File(targetDir, "$base ($n).$ext")
                }
                tmp.copyTo(candidate, overwrite = false)
            } else {
                tmp.copyTo(dest, overwrite = false)
            }
            uploadedNames.put(dest.name)
        }
        return json(JSONObject().put("ok", true).put("uploaded", uploadedNames))
    }

    private fun handleMkdir(session: IHTTPSession): Response {
        val target = resolveWritableDir(bodyAsJson(session).getString("path"))
        return json(JSONObject().put("ok", target.exists() || target.mkdirs()))
    }

    private fun deleteRecursively(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursively(it) }
        f.delete()
    }

    private fun handleDelete(session: IHTTPSession): Response {
        val paths = bodyAsJson(session).getJSONArray("paths")
        for (i in 0 until paths.length()) {
            deleteRecursively(resolveWritableDir(paths.getString(i)))
        }
        return json(JSONObject().put("ok", true))
    }

    private fun handleMove(session: IHTTPSession): Response {
        val body = bodyAsJson(session)
        val src = resolveWritableDir(body.getString("src"))
        val dstParent = resolveWritableDir(File(body.getString("dst")).parent ?: "/")
        val dstName = PathGuard.safeFileName(File(body.getString("dst")).name)
            ?: return errorJson(Response.Status.BAD_REQUEST, "Unsafe destination name")
        val dst = File(dstParent, dstName)
        if (src == dst) return errorJson(Response.Status.BAD_REQUEST, "Source and destination are the same")
        if (dst.exists()) return errorJson(Response.Status.CONFLICT, "Destination already exists")
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = false)
            deleteRecursively(src)
        }
        return json(JSONObject().put("ok", true))
    }

    private fun handleRename(session: IHTTPSession): Response {
        val body = bodyAsJson(session)
        val src = resolveWritableDir(body.getString("path"))
        val newName = PathGuard.safeFileName(body.getString("newName"))
            ?: return errorJson(Response.Status.BAD_REQUEST, "Unsafe name")
        val dst = File(src.parentFile, newName)
        if (dst.exists()) return errorJson(Response.Status.CONFLICT, "Name already in use")
        return json(JSONObject().put("ok", src.renameTo(dst)))
    }

    private fun handleClipboardGet(): Response {
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = if (cm.hasPrimaryClip() && cm.primaryClip!!.itemCount > 0) cm.primaryClip!!.getItemAt(0).coerceToText(appContext).toString() else ""
        return json(JSONObject().put("content", text))
    }

    private fun handleClipboardSet(session: IHTTPSession): Response {
        val text = bodyAsJson(session).optString("content", "")
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Flishly", text))
        return json(JSONObject().put("ok", true))
    }
}
