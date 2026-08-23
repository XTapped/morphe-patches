package com.github.xtapped.patches.motioncam

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.SupportedAbi
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.rawResourcePatch
import com.android.tools.smali.dexlib2.AccessFlags

private val MOTIONCAM_COMPATIBILITY = Compatibility(
    name = "MotionCam Pro Trial",
    packageName = "com.motioncam",
    apkFileType = ApkFileType.APKS,
    targets = listOf(
        AppTarget(
            version = "5.0.8-trial",
            versionCodes = mapOf(SupportedAbi.ARM64_V8A to 3308)
        )
    )
)

private object PhotoExportLimiterFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "L",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf(
        "trial_photo_export_limiter",
        "completed_photo_exports",
        "pending_photo_exports"
    )
)

private object LicenseFingerprint : Fingerprint(
    definingClass = "Lcom/motioncam/pro/CameraController;",
    name = "isLicensed",
    returnType = "Z",
    parameters = emptyList()
)

private fun ByteArray.findAll(pattern: ByteArray): List<Int> {
    if (pattern.isEmpty() || pattern.size > size) return emptyList()

    val matches = mutableListOf<Int>()
    for (start in 0..size - pattern.size) {
        var matchesPattern = true
        for (offset in pattern.indices) {
            if (this[start + offset] != pattern[offset]) {
                matchesPattern = false
                break
            }
        }
        if (matchesPattern) matches += start
    }
    return matches
}

private val unlockRecordingLimitPatch = rawResourcePatch {
    execute {
        val library = get("lib/arm64-v8a/libnative-camera-host.so")
        val bytes = library.readBytes()
        val pattern = byteArrayOf(
            0x09, 0x28, 0x93.toByte(), 0xd2.toByte(),
            0x88.toByte(), 0x03, 0x08, 0xcb.toByte(),
            0x49, 0xfc.toByte(), 0xa8.toByte(), 0xf2.toByte(),
            0x29, 0x00, 0xc0.toByte(), 0xf2.toByte(),
            0x1f, 0x01, 0x09, 0xeb.toByte(),
            0x0b, 0x04, 0x00, 0x54,
            0x68, 0xee.toByte(), 0x41, 0xf9.toByte()
        )
        val matches = bytes.findAll(pattern)

        if (matches.size != 1) {
            throw PatchException("Could not uniquely locate the trial recording limit")
        }

        val branchOffset = matches.single() + 20
        byteArrayOf(0x20, 0x00, 0x00, 0x14).copyInto(bytes, branchOffset)
        library.writeBytes(bytes)
    }
}

@Suppress("unused")
val unlockProPatch = bytecodePatch(
    name = "Unlock Pro",
    description = "Enable unlimited photo exports from captured RAW frames, remove the 5-second video recording limit, and enable pro tools for import, export, and advanced camera workflows",
    default = true
) {
    compatibleWith(MOTIONCAM_COMPATIBILITY)
    dependsOn(unlockRecordingLimitPatch)

    execute {
        PhotoExportLimiterFingerprint.method.addInstructions(
            0,
            """
                new-instance v0, Lyd1;
                const/4 v1, 0x0
                invoke-direct {v0, p0, v1}, Lyd1;-><init>(Landroid/content/Context;Z)V
                return-object v0
            """
        )

        LicenseFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """
        )
    }
}
