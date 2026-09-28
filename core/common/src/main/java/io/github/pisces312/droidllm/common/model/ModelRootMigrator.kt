package io.github.pisces312.droidllm.common.model

import java.io.File

/**
 * Safe model-root migration.
 *
 * Contract:
 * - Never deletes or overwrites anything that already exists under the target.
 * - Name collisions are reported and left in place; the user decides to skip or cancel.
 * - Source items are moved (rename, fallback copy+delete of the source copy only).
 */
object ModelRootMigrator {

    data class Plan(
        val sourceItems: List<File>,
        val toMove: List<File>,
        val conflicts: List<File>,
    ) {
        val isEmpty: Boolean get() = sourceItems.isEmpty()
        val hasConflicts: Boolean get() = conflicts.isNotEmpty()
    }

    data class Result(
        /** old absolute path -> new absolute path (top-level moved items). */
        val movedPaths: Map<String, String>,
        val skippedNames: List<String>,
        val errors: List<String>,
    ) {
        val isOk: Boolean get() = errors.isEmpty()
    }

    fun analyze(source: File, target: File): Plan {
        val items = source.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()
        val toMove = mutableListOf<File>()
        val conflicts = mutableListOf<File>()
        for (item in items) {
            if (File(target, item.name).exists()) conflicts += item else toMove += item
        }
        return Plan(sourceItems = items, toMove = toMove, conflicts = conflicts)
    }

    /** True when a and b are the same path or one contains the other. */
    fun isSameOrNested(a: File, b: File): Boolean {
        val ap = runCatching { a.canonicalFile }.getOrDefault(a.absoluteFile)
        val bp = runCatching { b.canonicalFile }.getOrDefault(b.absoluteFile)
        return ap == bp || ap.path.startsWith(bp.path + File.separator) || bp.path.startsWith(ap.path + File.separator)
    }

    /**
     * Moves non-conflicting top-level items from [source] into [target].
     * Conflicting names are never overwritten; they stay in [source] and are listed in
     * [Result.skippedNames].
     */
    fun migrate(source: File, target: File): Result {
        val plan = analyze(source, target)
        if (!target.exists() && !target.mkdirs()) {
            return Result(emptyMap(), emptyList(), listOf("无法创建目标目录：${target.absolutePath}"))
        }
        val moved = LinkedHashMap<String, String>()
        val skipped = plan.conflicts.map { it.name }.toMutableList()
        val errors = mutableListOf<String>()

        for (item in plan.toMove) {
            val dest = File(target, item.name)
            if (dest.exists()) {
                // Appeared after analyze — still never overwrite.
                skipped += item.name
                continue
            }
            if (moveRecursively(item, dest)) {
                moved[item.absolutePath] = dest.absolutePath
            } else {
                errors += "移动失败：${item.name}"
            }
        }
        return Result(movedPaths = moved, skippedNames = skipped, errors = errors)
    }

    /**
     * Remap a stored absolute path after a migration.
     * Exact moved-item match, or a descendant of a moved directory.
     * Handles both '/' and '\' so paths survive host-OS differences.
     */
    fun remapPath(path: String, moved: Map<String, String>): String? {
        moved[path]?.let { return it }
        for ((from, to) in moved) {
            val sep = if (from.contains('\\') || path.contains('\\')) "\\" else "/"
            if (path.startsWith(from + sep) || path.startsWith("$from/") || path.startsWith("$from\\")) {
                val suffix = path.substring(from.length)
                return to + suffix
            }
        }
        return null
    }

    /**
     * Moves a single file/dir to [dest]. Never overwrites an existing dest.
     * rename first, then copy+delete the source copy only.
     */
    fun moveItem(src: File, dest: File): Boolean {
        if (dest.exists()) return false
        dest.parentFile?.let { if (!it.exists() && !it.mkdirs()) return false }
        return moveRecursively(src, dest)
    }

    private fun moveRecursively(src: File, dest: File): Boolean {
        return try {
            if (src.renameTo(dest)) return true
            if (src.isDirectory) {
                if (!dest.mkdirs() && !dest.isDirectory) return false
                var ok = true
                val children = src.listFiles() ?: return false
                for (child in children) {
                    val d = File(dest, child.name)
                    if (d.exists()) {
                        ok = false
                        continue
                    }
                    if (!moveRecursively(child, d)) ok = false
                }
                // Only drop the source dir when every child moved and nothing is left behind.
                if (ok) {
                    val leftover = src.listFiles()
                    if (leftover == null || leftover.isEmpty()) src.delete()
                }
                return ok
            } else {
                src.copyTo(dest, overwrite = false)
                src.delete()
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
