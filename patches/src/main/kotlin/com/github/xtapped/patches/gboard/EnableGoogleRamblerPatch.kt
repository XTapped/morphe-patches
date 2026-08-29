package com.github.xtapped.patches.gboard

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstructionOrNull
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
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

private const val VOICE_SETTINGS =
    "Lcom/google/android/apps/inputmethod/latin/preference/VoiceSettingsFragment;"
private const val RAMBLER_RUNTIME =
    "Lcom/github/xtapped/extension/gboard/GoogleRamblerRuntime;"

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

private object FlagValueGetterFingerprint : Fingerprint(
    definingClass = "Lnyh;",
    name = "g",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = emptyList(),
    filters = listOf(string("Invalid flag: "))
)

private object VoiceSettingsLayoutFingerprint : Fingerprint(
    definingClass = VOICE_SETTINGS,
    name = "aB",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "I",
    parameters = emptyList(),
    filters = listOf(
        methodCall(
            definingClass = VOICE_SETTINGS,
            name = "aK",
            parameters = emptyList(),
            returnType = "Z"
        )
    )
)

private object VoiceSettingsSetupFingerprint : Fingerprint(
    definingClass = VOICE_SETTINGS,
    name = "ac",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(string("setupUnifiedLayout"))
)

private object VoiceSettingsCreateFingerprint : Fingerprint(
    definingClass = VOICE_SETTINGS,
    name = "f",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        methodCall(
            definingClass = VOICE_SETTINGS,
            name = "aK",
            parameters = emptyList(),
            returnType = "Z"
        )
    )
)

private object VoiceSettingsSubpageAvailabilityFingerprint : Fingerprint(
    definingClass = VOICE_SETTINGS,
    name = "aJ",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf("I"),
    filters = listOf(
        methodCall(
            definingClass = "Lewl;",
            name = "d",
            parameters = listOf("Landroid/content/Context;", "Ljava/util/Collection;"),
            returnType = "V"
        )
    )
)

private object VoiceSettingsSelectionWriteFingerprint : Fingerprint(
    definingClass = VOICE_SETTINGS,
    name = "aD",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf(
        "Z",
        "Lqiq;",
        "Lcom/google/android/libraries/inputmethod/preferencewidgets/CustomSelectorWithWidgetPreference;",
        "Lcom/google/android/libraries/inputmethod/preferencewidgets/CustomSelectorWithWidgetPreference;"
    ),
    filters = listOf(literal(2132019634L))
)

private object VoiceSettingsSelectionReadFingerprint : Fingerprint(
    definingClass = "Lmqz;",
    name = "a",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(literal(2132019634L))
)

private object DefaultSelectionFingerprint : Fingerprint(
    definingClass = "Lfbk;",
    name = "hU",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        string("onCreateExtension"),
        literal(2132019634L)
    )
)

private object AgenticDictationFeedbackFingerprint : Fingerprint(
    definingClass = "Lfbd;",
    name = "a",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        string(".GBOARD_JETSON"),
        string("jetson_feedback_trigger_id"),
        methodCall(
            definingClass = "Lrza;",
            name = "g",
            parameters = listOf("Landroid/content/Context;", "Llfo;"),
            returnType = "V"
        )
    )
)

@Suppress("unused")
val enableGoogleRamblerPatch = bytecodePatch(
    name = "Enable Google Rambler",
    description = "Exposes options to enable Google Rambler on any phone.",
    default = true
) {
    compatibleWith(GBOARD_COMPATIBILITY)
    dependsOn(gboardRuntimeExtensionPatch)

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

                val originalValueIndex = (valueIndex downTo 0).firstOrNull { index ->
                    val instruction = getInstructionOrNull(index)
                    instruction is BuilderInstruction11n && instruction.registerA == valueRegister
                } ?: throw PatchException(
                    "Could not resolve the original value for a Rambler flag"
                )
                val originalValue =
                    getInstruction<BuilderInstruction11n>(originalValueIndex).narrowLiteral
                val flagResultIndex = fingerprint.instructionMatches.last().index

                addInstruction(
                    flagResultIndex + 1,
                    "const/4 v$valueRegister, $originalValue"
                )

                if (valueInstruction is OneRegisterInstruction) {
                    replaceInstruction(valueIndex, "const/4 v$valueRegister, 0x1")
                } else {
                    addInstruction(valueIndex, "const/4 v$valueRegister, 0x1")
                }
            }
        }

        enableBooleanFlag(EnableRamblerAlToolbarFingerprint)
        enableBooleanFlag(EnableRamblerToolbarAtCursorPositionFingerprint)
        enableBooleanFlag(FilterRamblerContributedInputViewSessionFingerprint)

        AdActivationTypeFingerprint.method.apply {
            val valueIndex = AdActivationTypeFingerprint.instructionMatches[1].index
            val valueRegister = getInstruction<OneRegisterInstruction>(valueIndex).registerA
            replaceInstruction(valueIndex, "const-wide/16 v$valueRegister, 0x2")
        }

        FlagValueGetterFingerprint.method.applyRamblerFlagPolicy()

        VoiceSettingsLayoutFingerprint.method.applyScope(
            "enterVoiceSettingsScope",
            "exitVoiceSettingsScope"
        )
        VoiceSettingsSetupFingerprint.method.applyScope(
            "enterVoiceSettingsScope",
            "exitVoiceSettingsScope"
        )
        VoiceSettingsCreateFingerprint.method.applyScope(
            "enterVoiceSettingsScope",
            "exitVoiceSettingsScope"
        )

        // aJ() is called only for the Rambler and Standard detail screens. Its stock
        // implementation inflates each page off-screen and runs the full preference
        // contributor set as a preflight. On unsupported devices that detached preflight is
        // unsafe. Returning true keeps the row click handlers installed; those handlers are
        // also the stock path that persists the Rambler/Standard choice before navigating.
        VoiceSettingsSubpageAvailabilityFingerprint.method.bypassUnsupportedSubpageProbe()

        DefaultSelectionFingerprint.method.applyScope(
            "enterDefaultSelectionSuppression",
            "exitDefaultSelectionSuppression"
        )

        VoiceSettingsSelectionWriteFingerprint.method.observeBooleanParameter("p0")
        VoiceSettingsSelectionReadFingerprint.method.observeBooleanReturns()

        val feedbackCallIndex =
            AgenticDictationFeedbackFingerprint.instructionMatches.last().index
        AgenticDictationFeedbackFingerprint.method
            .bypassRamblerFeedbackExternalIntentGuard(feedbackCallIndex)
    }
}

private fun MutableMethod.applyRamblerFlagPolicy() {
    val instructions = implementation?.instructions
        ?: throw PatchException("Gboard flag getter has no implementation")
    if (implementation?.registerCount != 3) {
        throw PatchException("Unexpected Gboard flag getter register layout")
    }

    val returns = instructions.indices.filter { index ->
        instructions[index].opcode == Opcode.RETURN_OBJECT
    }
    if (returns.size != 1) {
        throw PatchException("Expected exactly one Gboard flag getter return")
    }

    val returnIndex = returns.single()
    val resultRegister = (instructions[returnIndex] as? OneRegisterInstruction)?.registerA
        ?: throw PatchException("Gboard flag getter return does not expose a register")
    if (resultRegister != 2) {
        throw PatchException("Unexpected Gboard flag getter result register")
    }

    addInstructions(
        returnIndex,
        """
            invoke-static {v1, v$resultRegister}, $RAMBLER_RUNTIME->applyFlagValue(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v$resultRegister
        """
    )
    addInstruction(0, "iget-object v1, p0, Lnyh;->a:Ljava/lang/String;")
}

private fun MutableMethod.applyScope(enterMethod: String, exitMethod: String) {
    val instructions = implementation?.instructions
        ?: throw PatchException("Scoped Gboard method has no implementation")
    val returns = instructions.indices.filter { index ->
        instructions[index].opcode in setOf(
            Opcode.RETURN,
            Opcode.RETURN_OBJECT,
            Opcode.RETURN_VOID,
            Opcode.RETURN_WIDE
        )
    }
    if (returns.isEmpty()) {
        throw PatchException("Scoped Gboard method has no return instruction")
    }

    returns.asReversed().forEach { index ->
        addInstruction(index, "invoke-static {}, $RAMBLER_RUNTIME->$exitMethod()V")
    }
    addInstruction(0, "invoke-static {}, $RAMBLER_RUNTIME->$enterMethod()V")
}

private fun MutableMethod.bypassUnsupportedSubpageProbe() {
    val instructions = implementation?.instructions
        ?: throw PatchException("Voice settings subpage probe has no implementation")
    if (implementation?.registerCount != 8 || instructions.size < 2) {
        throw PatchException("Unexpected Voice settings subpage probe layout")
    }
    if (
        instructions[0].opcode != Opcode.INVOKE_VIRTUAL ||
        instructions[1].opcode != Opcode.MOVE_RESULT_OBJECT
    ) {
        throw PatchException("Unexpected Voice settings subpage probe entry sequence")
    }

    replaceInstruction(0, "const/4 v0, 0x1")
    replaceInstruction(1, "return v0")
}

private fun MutableMethod.observeBooleanParameter(parameterRegister: String) {
    val instructions = implementation?.instructions
        ?: throw PatchException("Rambler selection writer has no implementation")
    val returns = instructions.indices.filter { index ->
        instructions[index].opcode == Opcode.RETURN_VOID
    }
    if (returns.isEmpty()) {
        throw PatchException("Rambler selection writer has no return")
    }

    returns.asReversed().forEach { index ->
        addInstruction(
            index,
            "invoke-static {$parameterRegister}, $RAMBLER_RUNTIME->updateOfficialSelection(Z)V"
        )
    }
}

private fun MutableMethod.observeBooleanReturns() {
    val instructions = implementation?.instructions
        ?: throw PatchException("Rambler selection reader has no implementation")
    val returns = instructions.indices.filter { index ->
        instructions[index].opcode == Opcode.RETURN
    }
    if (returns.isEmpty()) {
        throw PatchException("Rambler selection reader has no return")
    }

    returns.asReversed().forEach { index ->
        val register = (instructions[index] as? OneRegisterInstruction)?.registerA
            ?: throw PatchException("Rambler selection return does not expose a register")
        addInstruction(
            index,
            "invoke-static {v$register}, $RAMBLER_RUNTIME->updateOfficialSelection(Z)V"
        )
    }
}

private fun MutableMethod.bypassRamblerFeedbackExternalIntentGuard(callIndex: Int) {
    val call = getInstructionOrNull(callIndex) as? FiveRegisterInstruction
        ?: throw PatchException("Rambler feedback launcher call has an unexpected form")

    if (
        implementation?.registerCount != 5 ||
        call.registerCount != 2 ||
        call.registerC != 4 ||
        call.registerD != 1
    ) {
        throw PatchException("Unexpected Rambler feedback launcher register layout")
    }

    // Lrza.g() normally blocks before launching feedback when the generic
    // prevent_external_intents policy is active. Copy only its post-guard stock launch
    // sequence here, so the Rambler feedback row works without weakening that policy for
    // any other external intent in Gboard.
    replaceInstruction(callIndex, "new-instance v0, Llsh;")
    addInstructions(
        callIndex + 1,
        """
            const/4 v2, 0x0
            invoke-direct {v0, v4, v2, v2}, Llsh;-><init>(Landroid/content/Context;[B[B)V
            invoke-virtual {v1}, Llfo;->a()Llfp;
            move-result-object v2
            invoke-virtual {v0, v2}, Llsh;->l(Llfp;)V
        """
    )
}
