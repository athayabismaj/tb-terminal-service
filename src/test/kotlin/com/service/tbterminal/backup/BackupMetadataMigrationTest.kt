package com.service.tbterminal.backup

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupMetadataMigrationTest {
    @Test
    fun `backup response never contains database credentials or server path`() {
        val descriptor = BackupJobResponse.serializer().descriptor
        val fields = (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()
        assertFalse("password" in fields)
        assertFalse("databaseUrl" in fields)
        assertFalse("absolutePath" in fields)
        assertTrue(setOf("fileName", "fileSize", "sha256", "status").all(fields::contains))
    }
}
