package com.qingyu.imemodule.utils

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

object AssetUtils {
    @JvmStatic
    fun copyFileOrDir(context: Context, parent: String, path: String, destParent: String, overwrite: Boolean) {
        val assetManager = context.assets
        try {
            val assetPath = File(parent, path).path
            val assets = assetManager.list(assetPath)
            if (assets.isNullOrEmpty()) {
                // Files
                copyFile(context, parent, path, destParent, overwrite)
            } else {
                // Dirs
                val dir = File(destParent, path)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                for (asset in assets) {
                    val subPath = File(path, asset).path
                    copyFileOrDir(context, parent, subPath, destParent, overwrite)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun copyFile(context: Context, parentAssetPath: String, filename: String, destParent: String, overwrite: Boolean) {
        val assetManager = context.assets
        val inputStream: InputStream?
        val out: OutputStream?
        try {
            val assetPath = File(parentAssetPath, filename).path
            inputStream = assetManager.open(assetPath)
            val newFile = File(destParent, filename)
            if (newFile.exists() && !overwrite) return
            // 先写临时文件再改名：rename 在同一目录内是原子的，引擎因此只会看到
            // 「旧文件」或「完整的新文件」，不会读到拷到一半的 *.table.bin ——
            // 那会让 librime 在 mmap 上越界读，直接 SIGSEGV 而不是干净地报「词典缺失」。
            val tmpFile = File(destParent, "$filename.tmp")
            out = FileOutputStream(tmpFile)
            val buffer = ByteArray(1024)
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                out.write(buffer, 0, read)
            }
            inputStream.close()
            out.flush()
            out.close()
            if (!tmpFile.renameTo(newFile)) {
                tmpFile.delete()
                throw java.io.IOException("rename failed: ${tmpFile.name} -> ${newFile.path}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
