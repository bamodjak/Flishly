package com.filebridge.server

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
import java.io.FileOutputStream
import java.io.RandomAccessFile

class BridgeHttpServer(private val context: Context, port: Int) : NanoHTTPD(port) {

    private val rootDir: File = Environment.getExternalStorageDirectory()

    // ---- auth -------------------------------------------------------------

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val pass = ServerConfig.password
        if (pass.isEmpty()) return true
        val header = session.headers["authorization"] ?: return false
        if (!header.startsWith("Basic ")) return false
        return try {
            val decoded = String(Base64.decode(header.substring(6), Base64.DEFAULT))
            val parts = decoded.split(":", limit = 2)
            parts.size == 2 && parts[1] == pass
        } catch (e: Exception) {
            false
        }
    }

    private fun unauthorizedResponse(): Response {
        val r = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Authentication required")
        r.addHeader("WWW-Authenticate", "Basic realm=\"FileBridge\"")
        return r
    }

    // ---- path safety --------------------------------------------------------

    private fun resolvePath(raw: String?): File {
        val clean = (raw ?: "/").let { if (it.isEmpty()) "/" else it }
        val target = File(rootDir, clean.removePrefix("/"))
        val canonicalRoot = rootDir.canonicalPath
        val canonicalTarget = target.canonicalPath
        if (!canonicalTarget.startsWith(canonicalRoot)) {
            throw SecurityException("Path escapes root")
        }
        return File(canonicalTarget)
    }

    private fun json(obj: Any): Response {
        val r = newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString())
        r.addHeader("Access-Control-Allow-Origin", "*")
        return r
    }

    private fun errorJson(status: Response.Status, message: String): Response {
        val o = JSONObject()
        o.put("error", message)
        val r = newFixedLengthResponse(status, "application/json", o.toString())
        r.addHeader("Access-Control-Allow-Origin", "*")
        return r
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

    // ---- main dispatch ------------------------------------------------------

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri

        // static assets (no auth needed for the shell itself so the login
        // prompt can render; API calls below are protected)
        if (uri == "/" || uri == "/index.html") return serveAsset("web/index.html", "text/html")
        if (uri == "/app.js") return serveAsset("web/app.js", "application/javascript")
        if (uri == "/styles.css") return serveAsset("web/styles.css", "text/css")

        if (session.method == Method.OPTIONS) {
            val r = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            r.addHeader("Access-Control-Allow-Origin", "*")
            r.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
            r.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
            return r
        }

        if (!isAuthorized(session)) return unauthorizedResponse()

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
            errorJson(Response.Status.INTERNAL_ERROR, e.message ?: "Server error")
        }
    }

    private fun serveAsset(path: String, mime: String): Response {
        val stream = context.assets.open(path)
        return newChunkedResponse(Response.Status.OK, mime, stream)
    }

    private fun bodyAsJson(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val postData = files["postData"] ?: "{}"
        return JSONObject(postData)
    }

    // ---- handlers -----------------------------------------------------------

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

    private fun handleDownload(session: IHTTPSession): Response {
        val path = session.parameters["path"]?.firstOrNull() ?: return errorJson(Response.Status.BAD_REQUEST, "path required")
        val target = resolvePath(path)
        if (!target.exists() || target.isDirectory) return errorJson(Response.Status.NOT_FOUND, "File not found")

        val rangeHeader = session.headers["range"]
        val length = target.length()
        val mime = mimeFor(target.name)

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val parts = rangeHeader.substring(6).split("-")
            val start = parts[0].toLongOrNull() ?: 0
            val end = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toLong() else length - 1
            val chunkLen = end - start + 1
            val raf = RandomAccessFile(target, "r")
            raf.seek(start)
            val stream = FileInputStream(raf.fd)
            val r = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, stream, chunkLen)
            r.addHeader("Content-Range", "bytes $start-$end/$length")
            r.addHeader("Accept-Ranges", "bytes")
            r.addHeader("Content-Disposition", "inline; filename=\"${target.name}\"")
            return r
        }

        val stream = FileInputStream(target)
        val r = newFixedLengthResponse(Response.Status.OK, mime, stream, length)
        r.addHeader("Content-Disposition", "attachment; filename=\"${target.name}\"")
        r.addHeader("Accept-Ranges", "bytes")
        return r
    }

    private fun handleReadText(session: IHTTPSession): Response {
        val path = session.parameters["path"]?.firstOrNull() ?: return errorJson(Response.Status.BAD_REQUEST, "path required")
        val target = resolvePath(path)
        if (!target.exists() || target.isDirectory) return errorJson(Response.Status.NOT_FOUND, "File not found")
        if (target.length() > 2_000_000) return errorJson(Response.Status.BAD_REQUEST, "File too large to view as text")
        val content = target.readText()
        val o = JSONObject()
        o.put("content", content)
        return json(o)
    }

    private fun handleWriteText(session: IHTTPSession): Response {
        val body = bodyAsJson(session)
        val path = body.getString("path")
        val content = body.optString("content", "")
        val target = resolvePath(path)
        target.writeText(content)
        return json(JSONObject().put("ok", true))
    }

    private fun handleUpload(session: IHTTPSession): Response {
        val dirPath = session.parameters["path"]?.firstOrNull() ?: "/"
        val targetDir = resolvePath(dirPath)
        if (!targetDir.exists()) targetDir.mkdirs()

        val files = HashMap<String, String>()
        session.parseBody(files)

        val uploadedNames = JSONArray()
        // NanoHTTPD stores the temp file location keyed by form field name,
        // and the original client-supplied filename in the parameters map.
        for ((fieldName, tmpPath) in files) {
            val originalName = session.parameters[fieldName]?.firstOrNull() ?: File(tmpPath).name
            val safeName = File(originalName).name
            val dest = File(targetDir, safeName)
            File(tmpPath).copyTo(dest, overwrite = true)
            uploadedNames.put(safeName)
        }
        val o = JSONObject()
        o.put("ok", true)
        o.put("uploaded", uploadedNames)
        return json(o)
    }

    private fun handleMkdir(session: IHTTPSession): Response {
        val body = bodyAsJson(session)
        val path = body.getString("path")
        val target = resolvePath(path)
        val ok = target.exists() || target.mkdirs()
        return json(JSONObject().put("ok", ok))
    }

    private fun deleteRecursively(f: File) {
        if (f.isDirectory) {
            f.listFiles()?.forEach { deleteRecursively(it) }
        }
        f.delete()
    }

    private fun handleDelete(session: IHTTPSession): Response {
        val body = bodyAsJson(session)
        val paths = body.getJSONArray("paths")
        for (i in 0 until paths.length()) {
            val target = resolvePath(paths.getString(i))
            deleteRecursively(target)
        }
        return json(JSONObject().put("ok", true))
    }

    private fun handleMove(session: IHTTPSession): Response {
        val body = bodyAsJson(session)
        val src = resolvePath(body.getString("src"))
        val dst = resolvePath(body.getString("dst"))
        dst.parentFile?.mkdirs()
        val ok = src.renameTo(dst)
        if (!ok) {
            // fallback: copy + delete (cross-volume)
            src.copyTo(dst, overwrite = true)
            deleteRecursively(src)
        }
        return json(JSONObject().put("ok", true))
    }

    private fun handleRename(session: IHTTPSession): Response {
        val body = bodyAsJson(session)
        val src = resolvePath(body.getString("path"))
        val newName = body.getString("newName")
        val dst = File(src.parentFile, newName)
        val ok = src.renameTo(dst)
        return json(JSONObject().put("ok", ok))
    }

    private fun handleClipboardGet(): Response {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = if (cm.hasPrimaryClip() && cm.primaryClip!!.itemCount > 0) {
            cm.primaryClip!!.getItemAt(0).coerceToText(context).toString()
        } else ""
        return json(JSONObject().put("content", text))
    }

    private fun handleClipboardSet(session: IHTTPSession): Response {
        val body = bodyAsJson(session)
        val text = body.optString("content", "")
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("FileBridge", text))
        return json(JSONObject().put("ok", true))
    }
}
