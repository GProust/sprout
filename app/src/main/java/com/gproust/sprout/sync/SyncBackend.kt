package com.gproust.sprout.sync

import java.io.File

/**
 * The transport for the shared folder: where device snapshot files are read and
 * written. The reconciliation logic above is transport-neutral, so the only
 * thing a new backend (e.g. Google Drive) has to provide is "list / read / write
 * files in one folder".
 *
 * Contract:
 * - A device writes **only its own** file ([ownFileName]); it reads everyone's.
 * - File names follow `device-<deviceId>.json`; see [fileNameFor] / [deviceIdOf].
 *
 * The Google Drive implementation will live behind this interface — see
 * `docs/SYNC.md`.
 */
interface SyncBackend {
    /** Names of every device snapshot file currently in the shared folder. */
    suspend fun list(): List<String>

    /** Read a file's contents by name; null if it no longer exists. */
    suspend fun read(name: String): String?

    /** Create or overwrite a file with [content]. */
    suspend fun write(name: String, content: String)

    companion object {
        private const val PREFIX = "device-"
        private const val SUFFIX = ".json"

        fun fileNameFor(deviceId: String): String = "$PREFIX$deviceId$SUFFIX"

        /** The deviceId encoded in a file name, or null if it isn't a snapshot file. */
        fun deviceIdOf(name: String): String? =
            if (name.startsWith(PREFIX) && name.endsWith(SUFFIX)) {
                name.removePrefix(PREFIX).removeSuffix(SUFFIX)
            } else {
                null
            }
    }
}

/**
 * A [SyncBackend] backed by a local directory. Useful as a test double, and as
 * the basis for a plain "export/import to a folder" feature that needs no cloud
 * at all. The Drive backend mirrors this same shape over Drive's file API.
 */
class FolderSyncBackend(private val dir: File) : SyncBackend {

    override suspend fun list(): List<String> {
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && SyncBackend.deviceIdOf(it.name) != null }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    override suspend fun read(name: String): String? {
        val file = File(dir, name)
        return if (file.isFile) file.readText() else null
    }

    override suspend fun write(name: String, content: String) {
        if (!dir.exists()) dir.mkdirs()
        // Write to a temp file then rename, so a reader never sees a half-written file.
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(content)
        val target = File(dir, name)
        if (!tmp.renameTo(target)) {
            target.writeText(content)
            tmp.delete()
        }
    }
}
