package com.github.xtapped.patches.gboard

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val RAMBLER_DICTIONARY_RUNTIME =
    "Lcom/github/xtapped/extension/gboard/GoogleRamblerDictionaryRuntime;"
private const val OVERRIDE_FLAG_PREFERENCE =
    "Lcom/google/android/apps/inputmethod/latin/preference/OverrideFlagPreference;"

private object RamblerDictionaryFlagValueGetterFingerprint : Fingerprint(
    definingClass = "Lnyh;",
    name = "g",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = emptyList(),
    filters = listOf(string("Invalid flag: "))
)

private object RamblerSelectionReadForDictionaryFingerprint : Fingerprint(
    definingClass = "Lmqz;",
    name = "a",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;")
)

private object OverrideFlagPreferenceChangeFingerprint : Fingerprint(
    definingClass = OVERRIDE_FLAG_PREFERENCE,
    name = "k",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Z"),
    filters = listOf(
        methodCall(
            definingClass = "Lnyo;",
            name = "e",
            parameters = listOf("Ljava/lang/String;", "Z"),
            returnType = "V"
        )
    )
)

private object MuseContextConstructorFingerprint : Fingerprint(
    definingClass = "Licr;",
    name = "<init>",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Lvxm;",
        "Lvxm;",
        "Lvxm;"
    )
)

private object RamblerLearningControllerFingerprint : Fingerprint(
    definingClass = "Lrua;",
    name = "a",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Laafm;", "Laafp;")
)

private object AgenticDictationFeedbackAccessPointFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        methodCall(
            definingClass = "Losa;",
            name = "a",
            parameters = emptyList(),
            returnType = "Landroid/content/Context;"
        ),
        string("Keyboard context is unavailable; skipping Rambler feedback.")
    )
)

internal val gboardRamblerFunctionalityPatch = bytecodePatch(
    description = "Makes Rambler dictionary, learning, and feedback functional."
) {
    execute {
        RamblerDictionaryFlagValueGetterFingerprint.method.applyDictionaryFlagPolicy()
        RamblerSelectionReadForDictionaryFingerprint.method.observeSelectionAndContext()
        OverrideFlagPreferenceChangeFingerprint.method.persistDictionaryPreference()
        MuseContextConstructorFingerprint.method.augmentMusePersonalDictionary()
        RamblerLearningControllerFingerprint.method.observeRamblerCorrections()

        val feedbackContextIndex =
            AgenticDictationFeedbackAccessPointFingerprint.instructionMatches.first().index
        AgenticDictationFeedbackAccessPointFingerprint.method
            .replaceRamblerFeedbackContext(feedbackContextIndex)
    }
}

private fun MutableMethod.applyDictionaryFlagPolicy() {
    val instructions = implementation?.instructions
        ?: throw PatchException("Gboard dictionary flag getter has no implementation")
    if (implementation?.registerCount != 3) {
        throw PatchException("Unexpected Gboard dictionary flag getter register layout")
    }

    val returns = instructions.indices.filter { index ->
        instructions[index].opcode == Opcode.RETURN_OBJECT
    }
    if (returns.size != 1) {
        throw PatchException("Expected exactly one Gboard dictionary flag getter return")
    }

    val returnIndex = returns.single()
    val resultRegister = (instructions[returnIndex] as? OneRegisterInstruction)?.registerA
        ?: throw PatchException("Gboard dictionary flag return has no register")

    addInstructions(
        returnIndex,
        """
            invoke-static {v1, v$resultRegister}, $RAMBLER_DICTIONARY_RUNTIME->applyDictionaryFlagValue(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v$resultRegister
        """
    )
    addInstruction(0, "iget-object v1, p0, Lnyh;->a:Ljava/lang/String;")
}

private fun MutableMethod.observeSelectionAndContext() {
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
            ?: throw PatchException("Rambler selection return has no register")
        addInstruction(
            index,
            "invoke-static {p0, v$register}, $RAMBLER_DICTIONARY_RUNTIME->observeSelection(Landroid/content/Context;Z)V"
        )
    }
}

private fun MutableMethod.persistDictionaryPreference() {
    val instructions = implementation?.instructions
        ?: throw PatchException("OverrideFlagPreference setter has no implementation")
    if (implementation?.registerCount != 3 || instructions.size != 10) {
        throw PatchException("Unexpected OverrideFlagPreference setter layout")
    }
    if (
        instructions[0].opcode != Opcode.INVOKE_SUPER ||
        instructions[8].opcode != Opcode.INVOKE_INTERFACE ||
        instructions[9].opcode != Opcode.RETURN_VOID
    ) {
        throw PatchException("Unexpected OverrideFlagPreference setter sequence")
    }

    addInstruction(
        0,
        "invoke-static {p0, p1}, $RAMBLER_DICTIONARY_RUNTIME->onOverrideFlagChanged(Ljava/lang/Object;Z)V"
    )
}

private fun MutableMethod.augmentMusePersonalDictionary() {
    val instructions = implementation?.instructions
        ?: throw PatchException("Muse context constructor has no implementation")
    if (implementation?.registerCount != 6 || instructions.size != 7) {
        throw PatchException("Unexpected Muse context constructor layout")
    }
    if (
        instructions[0].opcode != Opcode.INVOKE_DIRECT ||
        instructions[4].opcode != Opcode.IPUT_OBJECT
    ) {
        throw PatchException("Unexpected Muse context dictionary assignment sequence")
    }

    addInstructions(
        1,
        """
            invoke-static {p4}, $RAMBLER_DICTIONARY_RUNTIME->mergePersonalDictionary(Ljava/util/Collection;)Ljava/util/Collection;
            move-result-object p4
            invoke-static {p4}, Lvxm;->o(Ljava/util/Collection;)Lvxm;
            move-result-object p4
        """
    )
}

private fun MutableMethod.observeRamblerCorrections() {
    val instructions = implementation?.instructions
        ?: throw PatchException("Rambler learning controller has no implementation")
    if (implementation?.registerCount != 11 || instructions.size < 80) {
        throw PatchException("Unexpected Rambler learning controller layout")
    }
    if (instructions[0].opcode != Opcode.SGET_OBJECT) {
        throw PatchException("Unexpected Rambler learning controller entry sequence")
    }

    addInstruction(
        0,
        "invoke-static {p2}, $RAMBLER_DICTIONARY_RUNTIME->recordRamblerCorrections(Ljava/lang/Object;)V"
    )
}

private fun MutableMethod.replaceRamblerFeedbackContext(contextCallIndex: Int) {
    val instructions = implementation?.instructions
        ?: throw PatchException("Rambler feedback access point has no implementation")
    if (implementation?.registerCount != 7 || contextCallIndex + 1 >= instructions.size) {
        throw PatchException("Unexpected Rambler feedback access-point layout")
    }
    if (
        instructions[contextCallIndex].opcode != Opcode.INVOKE_STATIC ||
        instructions[contextCallIndex + 1].opcode != Opcode.MOVE_RESULT_OBJECT
    ) {
        throw PatchException("Unexpected Rambler feedback context call sequence")
    }

    replaceInstruction(
        contextCallIndex,
        "invoke-static {}, $RAMBLER_DICTIONARY_RUNTIME->getApplicationContext()Landroid/content/Context;"
    )
}
