package com.example.myapplication
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.apache.poi.xwpf.usermodel.XWPFDocument
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 仓库层：对外只暴露“业务用得上的方法”，内部调用 SQLite 帮助类
 */
class DocumentRepository(
    private val db: DocumentDatabaseHelper,
    private val context: Context
) {

    init {
        // 初始化 PDFBox（防止第一次用时报未初始化）
        PDFBoxResourceLoader.init(context.applicationContext)
    }
    /** 全文检索 */
    suspend fun search(matchQuery: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            db.searchDocuments(matchQuery)
        }

    /** 单条写入（目前索引过程内部会用到） */
    suspend fun insertDocument(
        path: String,
        fileName: String,
        content: String,
        ext: String,
        dirpath: String
    ) = withContext(Dispatchers.IO) {
        db.insertDocument(path, fileName, content, ext, dirpath)
    }



    /**
     * 扫描指定目录（SAF treeUri），只处理给定扩展名的文件。
     * 对每个文件使用对应的内容读取器，将文本写入 t_content / t_content_idx。
     *
     * @param treeUri 通过系统目录选择器获得的 Uri
     * @param exts  要索引的扩展名列表，例如 ["txt", "md", "pdf"]
     * @param onProgress 进度回调，参数为 (已处理数量, 总数量)
     */
    suspend fun indexDirectory(
        treeUri: Uri,
        exts: List<String>,
        onProgress: (processed: Int, total: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext

        val normalizedExts = exts
            .map { it.trim().lowercase().removePrefix(".") }
            .filter { it.isNotEmpty() }
            .toSet()

        // 1. 先收集所有目标文件，便于计算总数
        val files = mutableListOf<DocumentFile>()

        fun walk(dir: DocumentFile) {
            for (child in dir.listFiles()) {
                if (child.isDirectory) {
                    walk(child)
                } else if (child.isFile) {
                    val name = child.name ?: continue
                    val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()

                    // 只收集指定后缀的文件
                    if (normalizedExts.isEmpty() || normalizedExts.contains(ext)) {
                        files += child
                    }
                }
            }
        }

        walk(root)

        val total = files.size
        var processed = 0
        onProgress(processed, total)

        // 2. 逐个读取内容并插入数据库
        for (file in files) {
            try {
                val uri = file.uri
                val name = file.name ?: "unknown"
                val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()

                // 用父目录名或根目录名作为 dirpath（你也可以改成 parent.uri.toString())
                val parentDirName = file.parentFile?.name ?: root.name ?: "root"
                val dirpath = parentDirName

                // ★ 根据不同后缀选择不同的内容读取实现
                val reader = getReaderForExt(ext)
                val content = reader.read(uri) ?: ""

                // 使用 uri.toString 作为唯一标识(id)，以后打开文件可以用这个 uri
                db.insertDocument(
                    path = uri.toString(),
                    fileName = name,
                    content = content,
                    ext = ext,
                    dirpath = dirpath
                )
            } catch (e: Exception) {
                // TODO: 这里可以往 t_error 表写一条错误数据（记录 dirpath/file_name/err_message 等）
            } finally {
                processed++
                onProgress(processed, total)
            }
        }
    }


    /**
     * 根据扩展名返回对应的内容读取器。
     * 目前：
     *  - txt/md/log 等 → 纯文本读取
     *  - pdf / docx → 预留 TODO（返回 null）
     *  - 其它默认按纯文本尝试读取
     */
    private fun getReaderForExt(ext: String): FileContentReader {
        val lower = ext.lowercase()
        return when (lower) {
            "txt", "md", "log", "csv", "json", "xml" ->
                TextFileContentReader(context)

            "pdf" ->
                PdfFileContentReader(context)

            "docx", "doc" ->
                DocxFileContentReader(context)

            else ->
                // 默认也尝试按文本读取，防止因为没配置 Reader 而完全跳过
                TextFileContentReader(context)
        }
    }



    /** 针对不同后缀的文件内容读取接口 */
    private interface FileContentReader {
        /**
         * @return 读取到的纯文本内容；如果不支持或解析失败可返回 null
         */
        suspend fun read(uri: Uri): String?
    }

    /** 默认的“纯文本文件”读取器，适用于 txt / md / log 等 */
    private inner class TextFileContentReader(
        private val context: Context
    ) : FileContentReader {
        override suspend fun read(uri: Uri): String? {
            return readTextFromDocument(uri)
        }
    }
    /** 预留：PDF 文本提取（暂未实现，只返回 null） */
    private inner class PdfFileContentReader(
        private val context: Context
    ) : FileContentReader {
        override suspend fun read(uri: Uri): String? {
            return readTextFromPDF(uri)
        }
    }

    /** 预留：DOCX 文本提取（暂未实现，只返回 null） */
    private inner class DocxFileContentReader(
        private val context: Context
    ) : FileContentReader {
        override suspend fun read(uri: Uri): String? {
            return readTextFromDocx(uri)
        }
    }

    /**===========================================================================不同类型文件内容提取接口注册========================================================================================**/

    /** 从 DocumentFile uri 读取纯文本内容（用于 txt/md 等） */
    private fun readTextFromDocument(uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input)).use { reader ->
                    val sb = StringBuilder()
                    var line: String?
                    while (true) {
                        line = reader.readLine()
                        if (line == null) break
                        sb.appendLine(line)
                    }
                    sb.toString()
                }
            }
        } catch (e: Exception) {
            null
        }
    }


    private fun readTextFromPDF(uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input).use { document ->
                    val stripper = PDFTextStripper()
                    stripper.getText(document)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }


    private fun readTextFromDocx(uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                XWPFDocument(input).use { doc ->
                    val sb = StringBuilder()

                    // 提取段落文本
                    for (para in doc.paragraphs) {
                        val text = para.text
                        if (!text.isNullOrBlank()) {
                            sb.appendLine(text)
                        }
                    }

                    // 如果你想连表格里的文字也一起提取，可以再补一段（可选）：
                    for (table in doc.tables) {
                        for (row in table.rows) {
                            for (cell in row.tableCells) {
                                val cellText = cell.text
                                if (!cellText.isNullOrBlank()) {
                                    sb.appendLine(cellText)
                                }
                            }
                        }
                    }
                    sb.toString()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**===========================================================================不同类型文件内容提取接口实现=======================================================================================**/

}
