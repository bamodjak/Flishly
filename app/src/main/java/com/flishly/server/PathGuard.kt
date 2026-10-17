package com.flishly.server

import java.io.File

object PathGuard {
    fun isInside(canonicalRoot: String, canonicalTarget: String): Boolean {
        if (canonicalTarget == canonicalRoot) return true
        return canonicalTarget.startsWith(canonicalRoot + File.separator)
    }

    fun isRoot(canonicalRoot: String, canonicalTarget: String): Boolean =
        canonicalTarget == canonicalRoot

    fun safeFileName(raw: String): String? {
        if (raw.contains('\u0000')) return null
        val name = File(raw).name
        if (name.isBlank() || name == "." || name == "..") return null
        return name
    }
}
