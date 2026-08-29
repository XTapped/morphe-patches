package com.github.xtapped.patches.gboard

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val RAMBLER_DICTIONARY_RUNTIME =
    "Lcom/github/xtapped/extension/gboard/GoogleRamblerDictionaryRuntime;"
private const val RAMBLER_HELP_RUNTIME =
    "Lcom/github/xtapped/extension/gboard/GoogleRamblerHelpRuntime;"
private const val OVERRIDE_FLAG_PREFERENCE =
    "Lcom/google/android/apps/inputmethod/latin/preference/OverrideFlagPreference;"
private const val VOICE_SETTINGS =
    "Lcom/google/android/apps/inputmethod/latin/preference/VoiceSettingsFragment;"
private const val JETSON_VOICE_SETTINGS =
    "Lcom/google/android/apps/inputmethod/latin/preference/JetsonVoiceSettingsFragment;"

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

private object RamblerSelectionWriteForDictionaryFingerprint : Fingerprint(
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

private object RamblerDictionaryQueryFingerprint : Fingerprint(
    definingClass = "Lqdb;",
    name = "d",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Lqcw;",
    parameters = listOf("Lrqe;", "Z"),
    filters = listOf(
        string("(shortcut IS NULL OR shortcut != ?)"),
        string("shortcut = ?"),
        string("rambler"),
        string("locale = ? AND ")
    )
)

private object ExternalIntentBlockFingerprint : Fingerprint(
    definingClass = "Loxu;",
    name = "d",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(string("Opening an external app is blocked."))
)

private object JetsonHelpAndFeedbackFingerprint : Fingerprint(
    definingClass = JETSON_VOICE_SETTINGS,
    name = "aA",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf("Landroidx/preference/Preference;"),
    filters = listOf(
        literal(2132019737L),
        methodCall(
            definingClass = "Lfbd;",
            name = "a",
            parameters = listOf("Landroid/content/Context;"),
            returnType = "V"
        )
    )
)

internal val gboardRamblerFunctionalityPatch = bytecodePatch(
    description = "Makes Rambler dictionary, learning, and feedback functional."
) {
    execute {
        RamblerDictionaryFlagValueGetterFingerprint.method.applyDictionaryFlagPolicy()
        RamblerSelectionReadForDictionaryFingerprint.method.observeSelectionAndContext()
        RamblerSelectionWriteForDictionaryFingerprint.method.observeSelectionWrite()
        OverrideFlagPreferenceChangeFingerprint.method.persistDictionaryPreference()
        MuseContextConstructorFingerprint.method.augmentMusePersonalDictionary()
        RamblerLearningControllerFingerprint.method.observeRamblerCorrections()
        RamblerDictionaryQueryFingerprint.method.ignoreLocaleForRamblerRows()

        ExternalIntentBlockFingerprint.method.adjustRamblerHelpExternalIntentBlock()
        val helpCallIndex = JetsonHelpAndFeedbackFingerprint.instructionMatches.last().index
        JetsonHelpAndFeedbackFingerprint.method.routeToStockHelpAndFeedback(helpCallIndex)
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

private fun MutableMethod.observeSelectionWrite() {
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
            "invoke-static {p0}, $RAMBLER_DICTIONARY_RUNTIME->observeSelectionValue(Z)V"
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

private fun MutableMethod.ignoreLocaleForRamblerRows() {
    val instructions = implementation?.instructions
        ?: throw PatchException("Rambler dictionary query has no implementation")
    if (implementation?.registerCount != 16 || instructions.size != 56) {
        throw PatchException("Unexpected Rambler dictionary query layout")
    }
    if (
        instructions[0].opcode != Opcode.CONST_4 ||
        instructions[1].opcode != Opcode.IF_EQ ||
        instructions[2].opcode != Opcode.CONST_STRING ||
        instructions[4].opcode != Opcode.CONST_STRING ||
        instructions[5].opcode != Opcode.CONST_STRING
    ) {
        throw PatchException("Unexpected Rambler dictionary query entry sequence")
    }

    // The stock Rambler-only query still filters by the language selected when the
    // dictionary screen was opened. Learned Rambler entries are tagged with the language
    // active during dictation, so that hides valid learned words after switching languages.
    // Nulling only the language argument when showRamblerOnly is true preserves the stock
    // shortcut="rambler" filter while leaving every normal personal-dictionary query intact.
    addInstructions(
        0,
        """
            if-eqz p2, :keep_rambler_dictionary_locale
            const/4 p1, 0x0
            :keep_rambler_dictionary_locale
            nop
        """
    )
}

private fun MutableMethod.adjustRamblerHelpExternalIntentBlock() {
    val instructions = implementation?.instructions
        ?: throw PatchException("External-intent policy method has no implementation")
    if (implementation?.registerCount != 6) {
        throw PatchException("Unexpected external-intent policy register layout")
    }

    val returns = instructions.indices.filter { index ->
        instructions[index].opcode == Opcode.RETURN
    }
    if (returns.size != 2) {
        throw PatchException("Unexpected external-intent policy return layout")
    }

    returns.asReversed().forEach { index ->
        val register = (instructions[index] as? OneRegisterInstruction)?.registerA
            ?: throw PatchException("External-intent policy return has no register")
        addInstructions(
            index,
            """
                invoke-static {v$register}, $RAMBLER_HELP_RUNTIME->adjustExternalIntentBlock(Z)Z
                move-result v$register
            """
        )
    }
}

private fun MutableMethod.routeToStockHelpAndFeedback(callIndex: Int) {
    val instructions = implementation?.instructions
        ?: throw PatchException("Rambler settings click handler has no implementation")
    val call = instructions.getOrNull(callIndex) as? FiveRegisterInstruction
        ?: throw PatchException("Rambler Help & feedback call has an unexpected form")

    if (
        implementation?.registerCount != 5 ||
        call.registerCount != 1 ||
        call.registerC != 3 ||
        callIndex < 2 ||
        instructions[callIndex - 2].opcode != Opcode.INVOKE_VIRTUAL ||
        instructions[callIndex - 1].opcode != Opcode.MOVE_RESULT_OBJECT
    ) {
        throw PatchException("Unexpected Rambler Help & feedback call layout")
    }

    // The stock Rambler row calls the Agentic feedback client, which can silently no-op in
    // a patched/unsupported-device setup. Route only this row through Gboard's normal Help &
    // feedback click handler. The extension opens that handler under a tightly scoped bypass
    // of Loxu.d(); all other external-intent policy checks keep their stock behavior.
    replaceInstruction(
        callIndex,
        "invoke-static {p0, p1}, $RAMBLER_HELP_RUNTIME->openHelpAndFeedback(Ljava/lang/Object;Ljava/lang/Object;)V"
    )
}
