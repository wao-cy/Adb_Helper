package com.adbhelper.app.core.shell

import com.adbhelper.app.ui.viewmodels.RemoteFile

object LsOutputParser {

    fun parse(output: String, basePath: String): List<RemoteFile> {
        val files = mutableListOf<RemoteFile>()
        val regex = Regex(
            """^([dl\-][rwxsSt\-]{9})\s+(\d+)\s+(\S+)\s+(\S+)\s+(\d+)\s+(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}|\w{3}\s+\d+\s+[\d:]+)\s+(.+)$"""
        )

        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            if (trimmed.startsWith("total")) continue

            val match = regex.find(trimmed) ?: continue
            val perms = match.groupValues[1]
            val owner = match.groupValues[3]
            val group = match.groupValues[4]
            val size = match.groupValues[5].toLongOrNull() ?: 0
            val date = match.groupValues[6]
            val rawName = match.groupValues[7].trim()

            val isLink = perms.startsWith("l")
            val name: String
            val linkTarget: String?
            if (isLink) {
                val arrowIdx = rawName.indexOf(" -> ")
                if (arrowIdx >= 0) {
                    name = rawName.substring(0, arrowIdx).trim()
                    linkTarget = rawName.substring(arrowIdx + 4).trim()
                } else {
                    name = rawName.trim()
                    linkTarget = null
                }
            } else {
                name = rawName.trim()
                linkTarget = null
            }

            if (name == "." || name == "..") continue

            val isDir = perms.startsWith("d")
            val fullPath = "${basePath.trimEnd('/')}/$name"

            files.add(
                RemoteFile(
                    name = name,
                    path = fullPath,
                    isDirectory = isDir,
                    size = size,
                    permissions = perms.substring(1),
                    owner = owner,
                    group = group,
                    modifiedDate = date,
                    isHidden = name.startsWith("."),
                    isSymlink = isLink,
                    linkTarget = linkTarget
                )
            )
        }

        return files
    }

    /**
     * 批量判断符号链接目标是文件还是目录。
     * 用一条 shell 命令批量 stat 所有链接，避免逐个执行的开销。
     * 对于无权限的链接，默认视为目录（可导航）。
     */
    suspend fun resolveSymlinkTypes(
        files: MutableList<RemoteFile>,
        execShell: suspend (String) -> ShellResult
    ) {
        val symlinks = files.filter { it.isSymlink && it.linkTarget != null }
        if (symlinks.isEmpty()) return

        try {
            val paths = symlinks.joinToString(" ") { "\"${it.path}\"" }
            val output = execShell("stat -c '%F' $paths 2>/dev/null").output
            val types = output.lines().map { it.trim().lowercase() }

            symlinks.forEachIndexed { index, symlink ->
                if (index < types.size) {
                    val type = types[index]
                    val targetIsDir = type.contains("directory")
                    val i = files.indexOf(symlink)
                    if (i >= 0) {
                        files[i] = symlink.copy(isDirectory = targetIsDir)
                    }
                }
            }
        } catch (_: Exception) {
            val knownDirPrefixes = listOf(
                "/system/", "/product/", "/vendor/", "/data/", "/storage/",
                "/mnt/", "/proc/", "/sys/", "/dev/"
            )
            val knownFileExts = listOf(".txt", ".conf", ".rc", ".xml", ".json", ".prop", ".cfg")
            for (symlink in symlinks) {
                val target = symlink.linkTarget ?: continue
                val isLikelyDir = knownDirPrefixes.any {
                    target.startsWith(it) && !knownFileExts.any { ext -> target.endsWith(ext) }
                }
                val i = files.indexOf(symlink)
                if (i >= 0) {
                    files[i] = symlink.copy(isDirectory = isLikelyDir)
                }
            }
        }
    }
}
