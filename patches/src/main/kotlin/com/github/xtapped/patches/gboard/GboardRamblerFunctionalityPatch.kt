package com.github.xtapped.patches.gboard

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.fieldAccess
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
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
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

private object RamblerWordLearningFingerprint : Fingerprint(
    definingClass = "Lfbk;",
    name = "j",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Laaar;", "Lrrj;", "Ljava/lang/String;"),
    filters = listOf(
        fieldAccess(
            definingClass = "Lfbd;",
            name = "b",
            type = "Ljava/util/concurrent/atomic/AtomicReference;"
        )
    )
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

internal val gboardRamblerFunctionalityPatch = bytecodePatch(
    description = "Makes Rambler dictionary, learning, and feedback functional."
) {
    execute {
        RamblerSelectionContextFingerprint.method.observeRamblerContext()

        OverrideFlagPreferenceInitialStateFingerprint.method.restoreDictionaryPreferenceState()
        OverrideFlagPreferenceChangeFingerprint.method.persistDictionaryPreference()

        val museConstructorCallIndex = MuseContextBuilderFingerprint.instructionMatches.last().index
        MuseContextBuilderFingerprint.method
            .augmentRamblerMusePersonalDictionary(museConstructorCallIndex)

        val wordLearningTargetIndex = RamblerWordLearningFingerprint.instructionMatches.first().index
        RamblerWordLearningFingerprint.method
            .observeRamblerWordLearning(wordLearningTargetIndex)

        RamblerDictionaryQueryFingerprint.method.ignoreLocaleForRamblerRows()

        val helpCallIndex = JetsonHelpAndFeedbackFingerprint.instructionMatches.last().index
        JetsonHelpAndFeedbackFingerprint.method.routeToStockHelpAndFeedback(helpCallIndex)
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

    // Capture the real Preference Context immediately after the superclass constructor, while
    // p1 still contains the Context argument.
    addInstruction(
        1,
        "invoke-static {p1}, $RAMBLER_DICTIONARY_RUNTIME->observeContext(Landroid/content/Context;)V"
    )

    // The entry insertion shifts the stock boolean move-result from index 9 to 10 and the
    // LinkableSwitchPreference.k() call from 10 to 11. p2 is dead after the superclass
    // constructor, so it can preserve the boolean while p1 is reused for the flag name.
    addInstructions(
        11,
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
            move-object v0, v$dictionaryRegister
            iget-object v2, v1, Lics;->b:Landroid/content/Context;
            invoke-static {v0, v2}, $RAMBLER_DICTIONARY_RUNTIME->mergePersonalDictionary(Ljava/util/Collection;Landroid/content/Context;)Ljava/util/Collection;
            move-result-object v0
            invoke-static {v0}, Lvxm;->o(Ljava/util/Collection;)Lvxm;
            move-result-object v$dictionaryRegister
        """
    )
}

private fun MutableMethod.observeRamblerWordLearning(targetIndex: Int) {
    val instructions = implementation?.instructions
        ?: throw PatchException("Rambler word learning handler has no implementation")
    val target = instructions.getOrNull(targetIndex)
        ?: throw PatchException("Rambler word learning target index out of bounds")

    if (
        target.opcode != Opcode.SGET_OBJECT ||
        targetIndex < 2 ||
        instructions[targetIndex - 2].opcode != Opcode.INVOKE_VIRTUAL ||
        instructions[targetIndex - 1].opcode != Opcode.MOVE_RESULT_OBJECT
    ) {
        throw PatchException("Unexpected Rambler word learning target layout")
    }

    addInstruction(
        targetIndex,
        "invoke-static {v8, v0}, $RAMBLER_DICTIONARY_RUNTIME->learnRamblerWords(Ljava/lang/String;Landroid/content/Context;)V"
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
        "invoke-static {p0}, $RAMBLER_HELP_RUNTIME->openVoiceTypingHelp(Landroid/content/Context;)V"
    )
}
