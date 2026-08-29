package com.github.xtapped.patches.gboard

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstructionOrNull
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private val GBOARD_COMPATIBILITY = Compatibility(
    name = "Gboard",
    packageName = "com.google.android.inputmethod.latin",
    apkFileType = ApkFileType.APK,
    targets = listOf(
        AppTarget(version = "18.1.3.962075747-release-arm64-v8a")
    )
)

private fun booleanFlagFingerprint(flag: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        string(flag),
        anyInstruction(
            opcode(Opcode.CONST_4),
            opcode(Opcode.INVOKE_STATIC),
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT)
    )
)

private val EnableRamblerAlToolbarFingerprint =
    booleanFlagFingerprint("enable_rambler_al_toolbar")
private val EnableRamblerToolbarAtCursorPositionFingerprint =
    booleanFlagFingerprint("enable_rambler_toolbar_at_cursor_position")
private val ShowRamblerDictSettingsFingerprint =
    booleanFlagFingerprint("show_rambler_dict_settings")
private val EnableAgenticDictationFingerprint =
    booleanFlagFingerprint("enable_agentic_dictation")
private val FilterRamblerContributedInputViewSessionFingerprint =
    booleanFlagFingerprint("filter_rambler_contributed_input_view_session")

private object AdActivationTypeFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        string("ad_activation_type"),
        opcode(Opcode.CONST_WIDE_16, MatchAfterImmediately()),
        opcode(Opcode.INVOKE_STATIC, MatchAfterImmediately()),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately())
    )
)

@Suppress("unused")
val enableGoogleRamblerPatch = bytecodePatch(
    name = "Enable Google Rambler",
    description = "Exposes options to enable Google Rambler on any phone.",
    default = true
) {
    compatibleWith(GBOARD_COMPATIBILITY)

    execute {
        fun enableBooleanFlag(fingerprint: Fingerprint) {
            fingerprint.method.apply {
                val valueIndex = fingerprint.instructionMatches[1].index
                val valueInstruction = getInstructionOrNull(valueIndex)
                val valueRegister = when (valueInstruction) {
                    is OneRegisterInstruction -> valueInstruction.registerA
                    is FiveRegisterInstruction -> valueInstruction.registerD
                    else -> throw PatchException(
                        "Unexpected Rambler flag instruction at index $valueIndex"
                    )
                }

                val originalValueIndex = indexOfFirstInstructionReversedOrThrow(
                    startIndex = valueIndex,
                    filter = {
                        this is BuilderInstruction11n && registerA == valueRegister
                    }
                )
                val originalValue =
                    getInstruction<BuilderInstruction11n>(originalValueIndex).narrowLiteral
                val fieldStoreIndex = fingerprint.instructionMatches.last().index

                addInstruction(
                    fieldStoreIndex + 1,
                    "const/4 v$valueRegister, $originalValue"
                )

                if (valueInstruction is OneRegisterInstruction) {
                    replaceInstruction(valueIndex, "const/4 v$valueRegister, 0x1")
                } else {
                    addInstruction(valueIndex, "const/4 v$valueRegister, 0x1")
                }
            }
        }

        // Patch higher offsets first when two flags share the same class initializer.
        enableBooleanFlag(EnableRamblerAlToolbarFingerprint)
        enableBooleanFlag(EnableRamblerToolbarAtCursorPositionFingerprint)
        enableBooleanFlag(ShowRamblerDictSettingsFingerprint)
        enableBooleanFlag(EnableAgenticDictationFingerprint)
        enableBooleanFlag(FilterRamblerContributedInputViewSessionFingerprint)

        AdActivationTypeFingerprint.method.apply {
            val valueIndex = AdActivationTypeFingerprint.instructionMatches[1].index
            val valueRegister = getInstruction<OneRegisterInstruction>(valueIndex).registerA
            replaceInstruction(valueIndex, "const-wide/16 v$valueRegister, 0x2")
        }
    }
}
