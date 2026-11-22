package com.example.wave.data.registry

import java.io.File
import java.io.IOException

/**
 * The newest verified registry, saved on the phone so it survives restarts and works offline.
 * Stored as three plain files in [dir]: the raw signed bytes, the key id and the base64url signature.
 * It's checked again every time it's read (by the caller's verifier): a damaged or edited copy is never trusted.
 */
class RegistryCache(private val dir: File) {

    private val bytesFile = File(dir, "registry.json")
    private val keyFile = File(dir, "registry.keyid")
    private val sigFile = File(dir, "registry.sig")

    /** Null when nothing is saved, or a file is missing, empty or unreadable. */
    fun read(): SignedRegistry? = try {
        val bytes = bytesFile.readBytes()
        val keyId = keyFile.readText()
        val sig = sigFile.readText()
        if (bytes.isEmpty() || keyId.isEmpty() || sig.isEmpty()) null else SignedRegistry(bytes, keyId, sig)
    } catch (_: IOException) {
        null
    }

    /** Each file is written to a .tmp first, then renamed; registry.json is replaced last. */
    fun write(file: SignedRegistry) {
        dir.mkdirs()
        replace(keyFile, file.keyId.toByteArray(Charsets.UTF_8))
        replace(sigFile, file.signatureB64Url.toByteArray(Charsets.UTF_8))
        replace(bytesFile, file.bytes)
    }

    private fun replace(target: File, content: ByteArray) {
        val tmp = File(dir, "${target.name}.tmp")
        tmp.writeBytes(content)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("Couldn't save ${target.name}")
            }
        }
    }
}
