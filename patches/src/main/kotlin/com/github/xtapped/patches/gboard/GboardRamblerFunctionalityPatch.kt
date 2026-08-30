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
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

private const val RAMBLER_DICTIONARY_RUNTIME =
    "Lcom/github/xtapped/extension/gboard/GoogleRamblerDictionaryRuntime;"
private const val RAMBLER_HELP_RUNTIME =
    "Lcom/github/xtapped/extension/gboard/GoogleRamblerHelpRuntime;"
private const val OVERRIDE_FLAG_PREFERENCE =
    "Lcom/google/android/apps/inputmethod/latin/preference/OverrideFlagPreference;"
private const val JETSON_VOICE_SETTINGS =
    "Lcom/google/android/apps/inputmethod/latin/preference/JetsonVoiceSettingsFragment;"

private object RamblerSelectionContextFingerprint : Fingerprint(
    definingClass = "Lmqz;",
    name = "a",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(literal(2132019634L))
)

private object OverrideFlagPreferenceInitialStateFingerprint : Fingerprint(
    definingClass = OVERRIDE_FLAG_PREFERENCE,
    name = "<init>",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;"),
    filters = listOf(
        methodCall(
            definingClass = "Lnye;",
            name = "o",
            parameters = listOf("Ljava/lang/String;"),
            returnType = "Lnyb;"
        ),
        methodCall(
            definingClass = "Lnyb;",
            name = "g",
            parameters = emptyList(),
            returnType = "Ljava/lang/Object;"
        )
    )
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

private object MuseContextBuilderFingerprint : Fingerprint(
    definingClass = "Leyw;",
    name = "call",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = emptyList(),
    filters = listOf(
        string("MuseContextModule.java"),
        string("pref_key_muse_name_dictionary"),
        methodCall(
            definingClass = "Licr;",
            name = "<init>",
            parameters = listOf(
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Lvxm;",
                "Lvxm;",
                "Lvxm;"
            ),
            returnType = "V"
        )
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

private object StockHelpAndFeedbackFingerprint : Fingerprint(
    definingClass = "Levx;",
    name = "b",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf("Landroidx/preference/Preference;"),
    filters = listOf(
        methodCall(
            definingClass = "Loxu;",
            name = "d",
            parameters = listOf("Landroid/content/Context;"),
            returnType = "Z"
        ),
        string("android_gboard")
    )
)

internal val gboardRamblerFunctionalityPatch = bytecodePatch(
    description = "Makes Rambler dictionary, learning, and feedback functional."
) {
    execute {
        // Keep runtime state on the Rambler-specific selection path. Avoid intercepting the
        // global flag getter: Gboard reads that method throughout process startup.
        RamblerSelectionContextFingerprint.method.observeRamblerContext()

        OverrideFlagPreferenceInitialStateFingerprint.method.restoreDictionaryPreferenceState()
        OverrideFlagPreferenceChangeFingerprint.method.persistDictionaryPreference()

        val museConstructorCallIndex = MuseContextBuilderFingerprint.instructionMatches.last().index
        MuseContextBuilderFingerprint.method
            .augmentRamblerMusePersonalDictionary(museConstructorCallIndex)

        RamblerLearningControllerFingerprint.method.observeRamblerCorrections()
        RamblerDictionaryQueryFingerprint.method.ignoreLocaleForRamblerRows()

        val helpCallIndex = JetsonHelpAndFeedbackFingerprint.instructionMatches.last().index
        JetsonHelpAndFeedbackFingerprint.method.routeToStockHelpAndFeedback(helpCallIndex)

        val stockHelpGuardIndex = StockHelpAndFeedbackFingerprint.instructionMatches.first().index
        StockHelpAndFeedbackFingerprint.method.scopeStockHelpExternalIntentGuard(stockHelpGuardIndex)
    }
}

private fun MutableMethod.observeRamblerContext() {
    val instructions = implementation?.instructions
        ?: throw PatchException("Rambler selection reader has no implementation")
    if (instructions.isEmpty()) {
        throw PatchException("Rambler selection reader is empty")
    }

    addInstruction(
        0,
        "invoke-static {p0}, $RAMBLER_DICTIONARY_RUNTIME->observeContext(Landroid/content/Context;)V"
    )
}

private fun MutableMethod.restoreDictionaryPreferenceState() {
    val instructions = implementation?.instructions
        ?: throw PatchException("OverrideFlagPreference constructor has no implementation")
    if (implementation?.registerCount != 3 || instructions.size != 12) {
        throw PatchException("Unexpected OverrideFlagPreference constructor layout")
    }
    if (
        instructions[0].opcode != Opcode.INVOKE_DIRECT ||
        instructions[4].opcode != Opcode.IPUT_OBJECT ||
        instructions[8].opcode != Opcode.INVOKE_VIRTUAL ||
        instructions[9].opcode != Opcode.MOVE_RESULT ||
        instructions[10].opcode != Opcode.INVOKE_SUPER
    ) {
        throw PatchException("Unexpected OverrideFlagPreference constructor sequence")
    }

    // The stock preference reads a production flag whose override is not reliable across
    // processes on unsupported devices. p2 is no longer needed after the superclass
    // constructor, so use it to preserve the resolved boolean while resolving the flag name.
    addInstructions(
        10,
        """
            invoke-static {p1}, $RAMBLER_DICTIONARY_RUNTIME->observeContext(Landroid/content/Context;)V
        """
    )

    // Context was held in p1 before the stock code reused that register for the boolean. The
    // call above therefore has to be made while the constructor argument is still intact.
    // Move it to the method entry instead, before any stock register reuse.
    removeInstruction(10)
    addInstruction(
        0,
        "invoke-static {p1}, $RAMBLER_DICTIONARY_RUNTIME->observeContext(Landroid/content/Context;)V"
    )

    // The insertion at method entry shifts the stock invoke-super checked above by one. Find
    // the move-result boolean and inject immediately after it rather than retaining a stale
    // source index.
    val currentInstructions = implementation?.instructions
        ?: throw PatchException("OverrideFlagPreference constructor disappeared")
    val booleanResultIndex = currentInstructions.indices.firstOrNull { index ->
        index > 0 &&
            currentInstructions[index].opcode == Opcode.MOVE_RESULT &&
            currentInstructions[index - 1].opcode == Opcode.INVOKE_VIRTUAL
    } ?: throw PatchException("Could not locate OverrideFlagPreference boolean result")

    addInstructions(
        booleanResultIndex + 1,
        """
            move p2, p1
            iget-object p1, p0, $OVERRIDE_FLAG_PREFERENCE->c:Lnyb;
            invoke-interface {p1}, Lnyb;->h()Ljava/lang/String;
            move-result-object p1
            invoke-static {p1, p2}, $RAMBLER_DICTIONARY_RUNTIME->resolveDictionaryPreference(Ljava/lang/String;Z)Z
            move-result p1
        """
    )
}

private fun MutableMethod.persistDictionaryPreference() {
    val instructions = implementation?.instructions
        ?: throw PatchException("OverrideFlagPreference setter has no implementation")
    if (implementation?.registerCount != 3 || instructions.size != 10) {
        throw PatchException("Unexpected OverrideFlagPreference setter layout")
    }
    if (
        instructions[0].opcode != Opcode.INVOKE_SUPER ||
        instructions[1].opcode != Opcode.IGET_OBJECT ||
        instructions[2].opcode != Opcode.IF_EQZ ||
        instructions[8].opcode != Opcode.INVOKE_INTERFACE ||
        instructions[9].opcode != Opcode.RETURN_VOID
    ) {
        throw PatchException("Unexpected OverrideFlagPreference setter sequence")
    }

    // The stock setter already has the exact Lnyb flag object in v1. Read its canonical name
    // instead of reflecting guessed Preference fields.
    addInstructions(
        3,
        """
            invoke-interface {v1}, Lnyb;->h()Ljava/lang/String;
            move-result-object v0
            invoke-static {v0, p1}, $RAMBLER_DICTIONARY_RUNTIME->onOverrideFlagChanged(Ljava/lang/String;Z)V
        """
    )
}

private fun MutableMethod.augmentRamblerMusePersonalDictionary(callIndex: Int) {
    val instructions = implementation?.instructions
        ?: throw PatchException("Muse context builder has no implementation")
    val call = instructions.getOrNull(callIndex) as? RegisterRangeInstruction
        ?: throw PatchException("Muse context constructor call is not a range invoke")

    if (
        implementation?.registerCount != 22 ||
        call.registerCount != 6 ||
        call.startRegister != 12
    ) {
        throw PatchException("Unexpected Muse context constructor register layout")
    }

    val dictionaryRegister = call.startRegister + 4
    addInstructions(
        callIndex,
        """
            invoke-static/range {v$dictionaryRegister .. v$dictionaryRegister}, $RAMBLER_DICTIONARY_RUNTIME->mergePersonalDictionary(Ljava/util/Collection;)Ljava/util/Collection;
            move-result-object v$dictionaryRegister
            invoke-static/range {v$dictionaryRegister .. v$dictionaryRegister}, Lvxm;->o(Ljava/util/Collection;)Lvxm;
            move-result-object v$dictionaryRegister
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

    replaceInstruction(
        callIndex,
        "invoke-static {p0, p1}, $RAMBLER_HELP_RUNTIME->openHelpAndFeedback(Ljava/lang/Object;Ljava/lang/Object;)V"
    )
}

private fun MutableMethod.scopeStockHelpExternalIntentGuard(callIndex: Int) {
    val instructions = implementation?.instructions
        ?: throw PatchException("Stock Help & feedback handler has no implementation")
    val call = instructions.getOrNull(callIndex) as? FiveRegisterInstruction
        ?: throw PatchException("Stock Help external-intent call has an unexpected form")
    val result = instructions.getOrNull(callIndex + 1) as? OneRegisterInstruction
        ?: throw PatchException("Stock Help external-intent result has an unexpected form")

    if (
        implementation?.registerCount != 52 ||
        call.registerCount != 1 ||
        instructions[callIndex].opcode != Opcode.INVOKE_STATIC ||
        instructions[callIndex + 1].opcode != Opcode.MOVE_RESULT ||
        result.registerA != 4
    ) {
        throw PatchException("Unexpected stock Help external-intent guard layout")
    }

    addInstructions(
        callIndex + 2,
        """
            invoke-static {v${result.registerA}}, $RAMBLER_HELP_RUNTIME->adjustExternalIntentBlock(Z)Z
            move-result v${result.registerA}
        """
    )
}
