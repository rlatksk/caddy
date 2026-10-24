package caddy.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class UnzipTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun unzip(zipFile: File, targetDir: File) {
        targetDir.mkdirs()
        val canonicalDest = targetDir.canonicalPath
        FileInputStream(zipFile).use { fis ->
            ZipInputStream(BufferedInputStream(fis)).use { zis ->
                var entry = zis.nextEntry
                val buffer = ByteArray(32768)
                while (entry != null) {
                    val entryFile = File(targetDir, entry.name)
                    if (!entryFile.canonicalPath.startsWith(canonicalDest)) {
                        throw SecurityException("Zip entry path traversal detected: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        entryFile.mkdirs()
                    } else {
                        entryFile.parentFile?.mkdirs()
                        FileOutputStream(entryFile).use { fos ->
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    }

    @Test
    fun testUnzipExtractsAndPreservesContent() {
        val workDir = tempFolder.newFolder("roms")
        val zipFile = File(workDir, "game.zip")

        // Create a test zip archive with game.iso inside
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            zos.putNextEntry(ZipEntry("game.iso"))
            zos.write("test-iso-game-content-bytes".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        assertTrue(zipFile.exists())
        assertEquals(1, workDir.listFiles()?.size)

        // Perform unzip
        unzip(zipFile, workDir)
        // Delete zip (matching postProcessFile in DownloadService)
        zipFile.delete()

        assertFalse("Original zip should be deleted", zipFile.exists())
        val extracted = File(workDir, "game.iso")
        assertTrue("Extracted file should exist", extracted.exists())
        assertEquals("test-iso-game-content-bytes", extracted.readText(Charsets.UTF_8))
    }

    @Test(expected = SecurityException::class)
    fun testZipSlipThrowsSecurityException() {
        val workDir = tempFolder.newFolder("target")
        val maliciousZip = File(tempFolder.root, "slip.zip")

        ZipOutputStream(BufferedOutputStream(FileOutputStream(maliciousZip))).use { zos ->
            zos.putNextEntry(ZipEntry("../outside.txt"))
            zos.write("evil".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        unzip(maliciousZip, workDir)
    }
}
