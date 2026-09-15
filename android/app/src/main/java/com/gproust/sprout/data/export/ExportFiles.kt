package com.gproust.sprout.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream
import java.time.LocalDate

/**
 * Getting a report off the phone, by the same route a replica already takes
 * (ADR-0007): Sprout writes the file and hands it to Android's share sheet, and
 * the parent picks the channel they already trust.
 *
 * Nothing is uploaded — the app still has no `INTERNET` permission and could
 * not upload it if it wanted to. The file sits in a cache directory until the
 * user chooses where it goes, and each export replaces the one before it: a
 * report is a courier, not an archive, and a health record left lying in the
 * cache is a health record left lying about.
 */
object ExportFiles {

    const val PDF_MIME = "application/pdf"

    private const val SHARE_DIR = "reports"

    /**
     * Writes a file where the share sheet can reach it and returns the
     * `content://` URI to hand out.
     *
     * The directory is emptied first: yesterday's report is of no use to
     * anyone, and keeping it would leave a second copy of a baby's health
     * record on the device for no reason.
     */
    fun stage(context: Context, fileName: String, write: (OutputStream) -> Unit): Uri {
        val dir = File(context.cacheDir, SHARE_DIR).apply {
            if (exists()) listFiles()?.forEach { it.delete() } else mkdirs()
        }
        val file = File(dir, fileName)
        file.outputStream().use(write)
        return FileProvider.getUriForFile(context, "${context.packageName}.sync", file)
    }

    /** The share sheet, with read access granted to whichever app the user picks. */
    fun shareIntent(uri: Uri, mimeType: String, subject: String): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
        subject,
    )

    /**
     * A file name that says whose record it is and what it covers, so a doctor
     * with three of them in a downloads folder can tell them apart.
     *
     * The baby's name is reduced to plain characters: it travels through mail
     * clients and file managers of every vintage, and a name with a slash in it
     * is a file that fails to save rather than a file with an odd name.
     */
    fun fileName(babyName: String, from: LocalDate, to: LocalDate, extension: String): String {
        val safe = babyName.map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .take(24)
            .ifBlank { "baby" }
        return "Sprout-$safe-${from}_$to.$extension"
    }
}
