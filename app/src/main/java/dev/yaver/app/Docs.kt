package dev.yaver.app

import android.content.Context
import android.net.Uri
import java.util.zip.ZipInputStream

/**
 * Getting readable text out of the documents people actually send.
 *
 * PDF needs a library — Android's own PdfRenderer draws pages as pictures and
 * exposes no text at all, which is useless for an agent. DOCX does not: it is
 * a zip with an XML file inside, and unpacking it by hand costs a few lines
 * and avoids a second dependency.
 *
 * Everything here is best-effort and says so. A scanned PDF has no text layer,
 * and returning silence would leave the agent inventing contents.
 */
object Docs {

    data class Extract(val text: String, val note: String?)

    private const val LIMIT = 200_000

    fun fromUri(context: Context, uri: Uri, mime: String?, name: String?): Extract {
        val kind = (mime ?: "").lowercase()
        val lower = (name ?: "").lowercase()
        return when {
            kind.contains("pdf") || lower.endsWith(".pdf") -> pdf(context, uri)
            lower.endsWith(".docx") ||
                kind.contains("wordprocessingml") -> docx(context, uri)
            kind.startsWith("text/") || kind.contains("json") || kind.contains("xml") ||
                lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".csv") ->
                plain(context, uri)
            else -> Extract("", "That file type cannot be read as text (${mime ?: "unknown type"}).")
        }
    }

    private fun plain(context: Context, uri: Uri): Extract = try {
        val text = context.contentResolver.openInputStream(uri)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        Extract(text.take(LIMIT), if (text.length > LIMIT) "Truncated to $LIMIT characters." else null)
    } catch (e: Exception) {
        Extract("", "Could not read the file: ${e.message}")
    }

    /**
     * A .docx is a zip; the body lives in word/document.xml. Paragraph and
     * break tags become newlines before the rest of the markup is stripped, or
     * the whole document arrives as one unreadable run.
     */
    private fun docx(context: Context, uri: Uri): Extract = try {
        var xml: String? = null
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "word/document.xml") {
                        xml = zip.readBytes().toString(Charsets.UTF_8)
                        break
                    }
                    zip.closeEntry()
                }
            }
        }
        val body = xml
        if (body == null) {
            Extract("", "That .docx has no readable document body.")
        } else {
            val text = body
                .replace(Regex("</w:p>"), "\n")
                .replace(Regex("<w:br[^>]*/>"), "\n")
                .replace(Regex("<w:tab[^>]*/>"), "\t")
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
            Extract(text.take(LIMIT), if (text.isBlank()) "The document appears to be empty." else null)
        }
    } catch (e: Exception) {
        Extract("", "Could not open the .docx: ${e.message}")
    }

    private fun pdf(context: Context, uri: Uri): Extract = try {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext)
        context.contentResolver.openInputStream(uri).use { stream ->
            if (stream == null) return Extract("", "Could not open the PDF.")
            val document = com.tom_roush.pdfbox.pdmodel.PDDocument.load(stream)
            val pages = document.numberOfPages
            val text = try {
                com.tom_roush.pdfbox.text.PDFTextStripper().getText(document)
            } finally {
                document.close()
            }
            val clean = text.replace(Regex("\n{3,}"), "\n\n").trim()
            Extract(
                clean.take(LIMIT),
                when {
                    clean.isBlank() ->
                        "$pages page(s), but no text layer — it is probably a scan, so nothing can be read from it."
                    clean.length > LIMIT -> "$pages page(s), truncated to $LIMIT characters."
                    else -> "$pages page(s)."
                }
            )
        }
    } catch (e: Throwable) {
        // Throwable rather than Exception: a malformed PDF can throw an Error
        // out of the parser, and taking the app down with it helps nobody.
        Extract("", "Could not read the PDF: ${e.message}")
    }
}
