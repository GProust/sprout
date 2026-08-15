package com.gproust.sprout.data.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Getting a replica — or an invitation — from one phone to the other, without a
 * server, a cloud folder, or a permission (ADR-0007, ADR-0008).
 *
 * Sprout writes the file and hands it to Android's share sheet; the user picks
 * the channel they already trust. Nothing is uploaded by the app itself, and
 * the file never leaves its private cache until the user chooses where it goes.
 *
 * Invitations and replicas share one extension on purpose. The receiving phone
 * can tell them apart by looking (a replica starts with a magic header, an
 * invitation is JSON), so the parents only ever deal with "a Sprout file"
 * instead of having to know which of two kinds they were sent.
 */
object SyncFiles {

    const val EXTENSION = "sprout"

    /**
     * A type of our own rather than `application/octet-stream`, so that opening
     * one of these files offers Sprout instead of every app that handles bytes.
     */
    const val MIME_TYPE = "application/vnd.sprout.sync"

    private const val SHARE_DIR = "sync"

    /**
     * Writes [bytes] where the share sheet can reach them and returns the
     * `content://` URI to hand out.
     *
     * The cache is deliberate: the file is a courier, not a copy to keep. Each
     * write replaces the previous one, and Android reclaims the directory when
     * it needs the space.
     */
    fun stage(context: Context, bytes: ByteArray, fileName: String): Uri {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val file = File(dir, "$fileName.$EXTENSION")
        file.writeBytes(bytes)
        return FileProvider.getUriForFile(context, authority(context), file)
    }

    /** The share sheet, with read access granted to whichever app the user picks. */
    fun shareIntent(uri: Uri, subject: String): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
        subject,
    )

    /** Reads a file the user picked, or that another app sent us. */
    fun read(context: Context, uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("cannot read $uri")

    /** The URI carried by an intent that opened or shared a file with Sprout. */
    fun incomingUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> null
    }

    private fun authority(context: Context) = "${context.packageName}.sync"
}
