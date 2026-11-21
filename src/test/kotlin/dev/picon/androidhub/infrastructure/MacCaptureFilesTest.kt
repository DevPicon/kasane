package dev.picon.androidhub.infrastructure

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.*

class MacCaptureFilesTest {
    @Test fun `recording is private and export preserves source and destination on failure`() = runTest {
        val files = MacCaptureFiles()
        val source = Path.of(files.createVideo())
        val directory = Files.createTempDirectory("capture-export-test-")
        try {
            val permissions = Files.getPosixFilePermissions(source.parent)
            assertFalse(permissions.any { it.name.startsWith("GROUP") || it.name.startsWith("OTHERS") })
            Files.writeString(source, "owned recording")
            val target = directory.resolve("result.mp4")
            Files.writeString(target, "existing recording")
            assertFails { files.save(source.toString(), target.toString()) }
            assertEquals("owned recording", Files.readString(source))
            assertEquals("existing recording", Files.readString(target))
            val saved = directory.resolve("new.mp4")
            files.save(source.toString(), saved.toString())
            assertEquals("owned recording", Files.readString(saved))
            assertFalse(Files.exists(source.parent))
            assertFailsWith<IllegalStateException> { files.discard(saved.toString()) }
        } finally { if (Files.exists(source.parent)) files.discard(source.toString()); directory.toFile().deleteRecursively() }
    }
    @Test fun `cleanup never deletes unrelated cache files or blocks a completed discard`() = runTest {
        val files = MacCaptureFiles()
        val source = Path.of(files.createVideo())
        try {
            Files.writeString(source, "owned video")
            val unrelated = source.parent.resolve("other-cache-entry")
            Files.writeString(unrelated, "keep")
            files.discard(source.toString())
            assertFalse(Files.exists(source))
            assertEquals("keep", Files.readString(unrelated))
        } finally { source.parent.toFile().deleteRecursively() }
    }

}
