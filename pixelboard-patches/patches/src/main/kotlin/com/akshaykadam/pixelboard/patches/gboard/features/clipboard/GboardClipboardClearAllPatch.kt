package com.akshaykadam.pixelboard.patches.gboard.features.clipboard

import com.akshaykadam.pixelboard.patches.gboard.shared.addFieldIfMissing
import com.akshaykadam.pixelboard.patches.gboard.shared.gboardPatchesExtensionCarrierPatch
import com.akshaykadam.pixelboard.patches.gboard.shared.isMethodReference
import com.akshaykadam.pixelboard.patches.gboard.shared.runtimeabi.RuntimeAbiCatalog
import com.akshaykadam.pixelboard.patches.gboard.shared.runtimeabi.RuntimeCallEmitter
import com.akshaykadam.pixelboard.patches.gboard.shared.runtimeabi.RuntimeCallId
import com.akshaykadam.pixelboard.patches.shared.BytecodePatchContext
import com.akshaykadam.pixelboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import com.akshaykadam.pixelboard.patches.shared.ExternalLabel
import com.akshaykadam.pixelboard.patches.shared.MutableMethod
import com.akshaykadam.pixelboard.patches.shared.addInstructionsWithLabels
import com.akshaykadam.pixelboard.patches.shared.bytecodePatch
import com.akshaykadam.pixelboard.patches.gboard.shared.addHelperMethodIfMissing
import com.android.tools.smali.dexlib2.AccessFlags

// Long-press on the clipboard's "Delete selected items" trash key
// (keycode CLIPBOARD_BATCH_DELETE) offers to clear every unpinned clip.
//
// 1. SoftKeyDef.g(ActionType) normally finds no LONG_PRESS action for the trash key.
//    The hook builds one from its PRESS action (same keycode, fired on the long-press
//    timer like the spacebar's), so Gboard's key tracker runs the long-press and drops
//    the PRESS that would otherwise follow on release. While nothing is selected Gboard
//    swaps in a greyed-out trash key with no actions; it gets a LONG_PRESS too.
// 2. ClipboardKeyboard's key handler catches that LONG_PRESS event before the
//    batch-delete case and hands it to GboardClipboardClearAllRuntime, which shows the
//    confirm dialog and calls back into the helpers added here.
// 3. The helpers delete through Gboard's own private batch delete, so its
//    "N items deleted · Undo" toast and cleanup still run, then leave edit mode.

private const val BATCH_DELETE_KEYCODE = -10111 // CLIPBOARD_BATCH_DELETE
private const val SOFT_KEY_DEF = "Lcom/google/android/libraries/inputmethod/metadata/SoftKeyDef;"
private const val ACTION_DEF = "Lcom/google/android/libraries/inputmethod/metadata/ActionDef;"
private const val CLIPBOARD_KEYBOARD = "Lcom/google/android/apps/inputmethod/libs/clipboard/ClipboardKeyboard;"
private const val SPARSE_ARRAY = "Landroid/util/SparseArray;"
private const val RECYCLER_VIEW = "Landroid/support/v7/widget/RecyclerView;"

private const val LONG_PRESS_FIELD = "pixelboardClearAllLongPress"
private const val LONG_PRESS_CHECKED_FIELD = "pixelboardClearAllChecked"
private const val LONG_PRESS_HELPER = "pixelboardClearAllLongPress"
private const val COUNT_HELPER = "pixelboardCountUnpinnedClips"
private const val CLEAR_HELPER = "pixelboardClearUnpinnedClips"

/** Obfuscated names for one Gboard build. Every member is checked before anything is patched. */
private data class ClearAllNames(
    val label: String,
    /** ActionType enum and its PRESS / LONG_PRESS constants. */
    val actionType: String,
    val pressField: String,
    val longPressField: String,
    /** ActionDef.Builder: no-arg constructor, reset, copyFrom(ActionDef), fireOnDown flag, build(). */
    val actionDefBuilder: String,
    val builderReset: String,
    val builderCopyFrom: String,
    val builderFireOnDownField: String,
    val builderBuild: String,
    /** ActionDef.firstKeyData() and the KeyData type with its int keycode field. */
    val actionDefKeyData: String,
    val keyData: String,
    val keyDataKeycodeField: String,
    /** SoftKeyDef.getAction(ActionType), its key id field, and ActionDef.Builder.setKeyData(int, Object, Object). */
    val softKeyDefGetAction: String,
    val softKeyDefIdField: String,
    val builderSetKeyData: String,
    val keyDataIntention: String,
    /**
     * Resource id of the greyed-out trash key Gboard shows in edit mode while nothing is
     * selected (SUB_CATEGORY_1/6 in the clipboard header key mapping). It has no actions.
     */
    val disabledTrashKeyId: Int,
    /** Key event: its action type field and KeyData[] field. */
    val keyEvent: String,
    val keyEventActionTypeField: String,
    val keyEventKeyDataField: String,
    /** ClipboardKeyboard.handle(KeyEvent), its adapter field and its private batch delete. */
    val clipboardHandleEvent: String,
    val clipboardAdapterField: String,
    val clipboardListField: String,
    val clipboardBatchDelete: String,
    /** ClipboardAdapter rows list, row type (0 = clip) and pinned check. */
    val clipboardAdapter: String,
    val adapterRowsField: String,
    val clipItem: String,
    val clipItemViewType: String,
    val clipItemPinned: String,
    /** Runnable posted after Gboard's batch delete (case 7 closes edit mode) and its executor. */
    val closeEditRunnable: String,
    val closeEditRunnableCase: Int,
    val uiExecutor: String,
    val uiExecutorField: String,
)

private val clearAllTargets = listOf(
    // Gboard 18.6.3
    ClearAllNames(
        label = "18.6.3",
        actionType = "Lpuc;",
        pressField = "a",
        longPressField = "b",
        actionDefBuilder = "Lpud;",
        builderReset = "k",
        builderCopyFrom = "i",
        builderFireOnDownField = "e",
        builderBuild = "c",
        actionDefKeyData = "b",
        keyData = "Lpva;",
        keyDataKeycodeField = "c",
        softKeyDefGetAction = "g",
        softKeyDefIdField = "f",
        builderSetKeyData = "s",
        keyDataIntention = "Lpuz;",
        disabledTrashKeyId = 0x7f0b0a40,
        keyEvent = "Loak;",
        keyEventActionTypeField = "a",
        keyEventKeyDataField = "b",
        clipboardHandleEvent = "m",
        clipboardAdapterField = "c",
        clipboardListField = "l",
        clipboardBatchDelete = "W",
        clipboardAdapter = "Lfob;",
        adapterRowsField = "n",
        clipItem = "Lfmy;",
        clipItemViewType = "g",
        clipItemPinned = "n",
        closeEditRunnable = "Lfly;",
        closeEditRunnableCase = 7,
        uiExecutor = "Lnho;",
        uiExecutorField = "b",
    ),
)

internal val gboardClipboardClearAllPatch = bytecodePatch(
    description = "Long-press the clipboard trash key to clear all unpinned clips after a confirm dialog.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    dependsOn(gboardPatchesExtensionCarrierPatch)

    execute {
        val names = clearAllTargets.firstOrNull { matches(it) } ?: return@execute
        patchSoftKeyDef(names)
        patchClipboardKeyboard(names)
    }
}

private fun BytecodePatchContext.hasMethod(classType: String, name: String, params: List<String>, returnType: String) =
    mutableClassDefByOrNull(classType)?.methods?.any {
        it.name == name && it.parameterTypes == params && it.returnType == returnType
    } == true

private fun BytecodePatchContext.hasField(classType: String, name: String, type: String) =
    mutableClassDefByOrNull(classType)?.fields?.any { it.name == name && it.type == type } == true

private fun BytecodePatchContext.matches(n: ClearAllNames): Boolean =
    hasField(n.actionType, n.pressField, n.actionType) &&
        hasField(n.actionType, n.longPressField, n.actionType) &&
        hasMethod(n.actionDefBuilder, "<init>", emptyList(), "V") &&
        hasMethod(n.actionDefBuilder, n.builderReset, emptyList(), "V") &&
        hasMethod(n.actionDefBuilder, n.builderCopyFrom, listOf(ACTION_DEF), "V") &&
        hasField(n.actionDefBuilder, n.builderFireOnDownField, "Z") &&
        hasField(n.actionDefBuilder, "a", n.actionType) &&
        hasMethod(n.actionDefBuilder, n.builderBuild, emptyList(), ACTION_DEF) &&
        hasMethod(ACTION_DEF, n.actionDefKeyData, emptyList(), n.keyData) &&
        hasField(ACTION_DEF, "f", "Lwhe;") &&
        hasField(n.keyData, n.keyDataKeycodeField, "I") &&
        hasMethod(SOFT_KEY_DEF, n.softKeyDefGetAction, listOf(n.actionType), ACTION_DEF) &&
        hasField(SOFT_KEY_DEF, n.softKeyDefIdField, "I") &&
        hasMethod(n.actionDefBuilder, n.builderSetKeyData, listOf("I", n.keyDataIntention, "Ljava/lang/Object;"), "V") &&
        hasField(n.keyEvent, n.keyEventActionTypeField, n.actionType) &&
        hasField(n.keyEvent, n.keyEventKeyDataField, "[${n.keyData}") &&
        hasMethod(CLIPBOARD_KEYBOARD, n.clipboardHandleEvent, listOf(n.keyEvent), "Z") &&
        hasField(CLIPBOARD_KEYBOARD, n.clipboardAdapterField, n.clipboardAdapter) &&
        hasField(CLIPBOARD_KEYBOARD, n.clipboardListField, RECYCLER_VIEW) &&
        hasMethod(CLIPBOARD_KEYBOARD, n.clipboardBatchDelete, listOf(SPARSE_ARRAY, "Z"), "V") &&
        hasField(n.clipboardAdapter, n.adapterRowsField, "Ljava/util/List;") &&
        hasMethod(n.clipItem, n.clipItemViewType, emptyList(), "I") &&
        hasMethod(n.clipItem, n.clipItemPinned, emptyList(), "Z") &&
        hasMethod(n.closeEditRunnable, "<init>", listOf("Ljava/lang/Object;", "I"), "V") &&
        hasField(n.uiExecutor, n.uiExecutorField, n.uiExecutor) &&
        hasMethod(n.uiExecutor, "execute", listOf("Ljava/lang/Runnable;"), "V")

private fun BytecodePatchContext.method(classType: String, name: String, params: List<String>, returnType: String): MutableMethod =
    mutableClassDefBy(classType).methods.first {
        it.name == name && it.parameterTypes == params && it.returnType == returnType
    }

private fun MutableMethod.alreadyCalls(reference: String): Boolean =
    implementation?.instructions?.any { it.isMethodReference(reference) } == true

private fun BytecodePatchContext.patchSoftKeyDef(n: ClearAllNames) {
    val actionType = n.actionType
    val softKeyDef = mutableClassDefBy(SOFT_KEY_DEF)
    val privateTransient = AccessFlags.PRIVATE.value or AccessFlags.TRANSIENT.value
    softKeyDef.addFieldIfMissing(LONG_PRESS_FIELD, ACTION_DEF, privateTransient)
    softKeyDef.addFieldIfMissing(LONG_PRESS_CHECKED_FIELD, "Z", privateTransient)

    // Returns a cached LONG_PRESS action sending CLIPBOARD_BATCH_DELETE for the trash key,
    // otherwise null. For the enabled trash key it is a copy of its PRESS action; for the
    // greyed-out one (no actions, nothing selected) it is built from scratch, so the
    // long-press works without a selection while a tap still does nothing. p0 = SoftKeyDef.
    addHelperMethodIfMissing(
        classType = SOFT_KEY_DEF,
        name = LONG_PRESS_HELPER,
        parameterTypes = emptyList(),
        returnType = ACTION_DEF,
        accessFlags = AccessFlags.PRIVATE.value,
        registerCount = 5,
        body = """
            iget-object v0, p0, $SOFT_KEY_DEF->$LONG_PRESS_FIELD:$ACTION_DEF
            if-eqz v0, :compute
            return-object v0
            :compute
            iget-boolean v0, p0, $SOFT_KEY_DEF->$LONG_PRESS_CHECKED_FIELD:Z
            if-eqz v0, :check
            const/4 v0, 0x0
            return-object v0
            :check
            const/4 v0, 0x1
            iput-boolean v0, p0, $SOFT_KEY_DEF->$LONG_PRESS_CHECKED_FIELD:Z
            sget-object v0, $actionType->${n.pressField}:$actionType
            invoke-virtual {p0, v0}, $SOFT_KEY_DEF->${n.softKeyDefGetAction}($actionType)$ACTION_DEF
            move-result-object v0
            if-eqz v0, :disabled
            iget-object v1, v0, $ACTION_DEF->f:Lwhe;
            if-eqz v1, :none
            invoke-interface {v1}, Ljava/util/List;->size()I
            move-result v1
            if-eqz v1, :none
            invoke-virtual {v0}, $ACTION_DEF->${n.actionDefKeyData}()${n.keyData}
            move-result-object v1
            if-eqz v1, :none
            iget v1, v1, ${n.keyData}->${n.keyDataKeycodeField}:I
            const v2, $BATCH_DELETE_KEYCODE
            if-ne v1, v2, :none
            new-instance v1, ${n.actionDefBuilder}
            invoke-direct {v1}, ${n.actionDefBuilder}-><init>()V
            invoke-virtual {v1}, ${n.actionDefBuilder}->${n.builderReset}()V
            invoke-virtual {v1, v0}, ${n.actionDefBuilder}->${n.builderCopyFrom}($ACTION_DEF)V
            goto :build
            :disabled
            iget v1, p0, $SOFT_KEY_DEF->${n.softKeyDefIdField}:I
            const v2, ${n.disabledTrashKeyId}
            if-ne v1, v2, :none
            new-instance v1, ${n.actionDefBuilder}
            invoke-direct {v1}, ${n.actionDefBuilder}-><init>()V
            invoke-virtual {v1}, ${n.actionDefBuilder}->${n.builderReset}()V
            const v2, $BATCH_DELETE_KEYCODE
            const/4 v3, 0x0
            invoke-virtual {v1, v2, v3, v3}, ${n.actionDefBuilder}->${n.builderSetKeyData}(I${n.keyDataIntention}Ljava/lang/Object;)V
            :build
            sget-object v2, $actionType->${n.longPressField}:$actionType
            iput-object v2, v1, ${n.actionDefBuilder}->a:$actionType
            const/4 v2, 0x1
            iput-boolean v2, v1, ${n.actionDefBuilder}->${n.builderFireOnDownField}:Z
            invoke-virtual {v1}, ${n.actionDefBuilder}->${n.builderBuild}()$ACTION_DEF
            move-result-object v1
            iput-object v1, p0, $SOFT_KEY_DEF->$LONG_PRESS_FIELD:$ACTION_DEF
            return-object v1
            :none
            const/4 v0, 0x0
            return-object v0
        """,
    )

    val helperReference = "$SOFT_KEY_DEF->$LONG_PRESS_HELPER()$ACTION_DEF"
    val getAction = method(SOFT_KEY_DEF, n.softKeyDefGetAction, listOf(actionType), ACTION_DEF)
    if (getAction.alreadyCalls(helperReference)) return
    val first = getAction.implementation!!.instructions.first()
    // p0 = SoftKeyDef, p1 = ActionType. Only LONG_PRESS lookups reach the helper.
    getAction.addInstructionsWithLabels(
        0,
        """
            sget-object v0, $actionType->${n.longPressField}:$actionType
            if-ne p1, v0, :pixelboard_clear_all_skip
            invoke-direct {p0}, $helperReference
            move-result-object v0
            if-eqz v0, :pixelboard_clear_all_skip
            return-object v0
        """.trimIndent(),
        ExternalLabel("pixelboard_clear_all_skip", first),
    )
}

private fun unpinnedLoop(n: ClearAllNames, collect: String): String = """
    iget-object v0, v0, ${n.clipboardAdapter}->${n.adapterRowsField}:Ljava/util/List;
    invoke-interface {v0}, Ljava/util/List;->size()I
    move-result v1
    const/4 v2, 0x0
    const/4 v3, 0x0
    :loop
    if-ge v2, v1, :done
    invoke-interface {v0, v2}, Ljava/util/List;->get(I)Ljava/lang/Object;
    move-result-object v4
    check-cast v4, ${n.clipItem}
    if-eqz v4, :next
    invoke-virtual {v4}, ${n.clipItem}->${n.clipItemViewType}()I
    move-result v5
    if-nez v5, :next
    invoke-virtual {v4}, ${n.clipItem}->${n.clipItemPinned}()Z
    move-result v5
    if-nez v5, :next
    $collect
    add-int/lit8 v3, v3, 0x1
    :next
    add-int/lit8 v2, v2, 0x1
    goto :loop
    :done
""".trimIndent()

private fun BytecodePatchContext.patchClipboardKeyboard(n: ClearAllNames) {
    val adapterField = "$CLIPBOARD_KEYBOARD->${n.clipboardAdapterField}:${n.clipboardAdapter}"

    // Number of unpinned clips in the list, or -1 without an adapter. p0 = ClipboardKeyboard.
    addHelperMethodIfMissing(
        classType = CLIPBOARD_KEYBOARD,
        name = COUNT_HELPER,
        parameterTypes = emptyList(),
        returnType = "I",
        accessFlags = AccessFlags.PUBLIC.value,
        registerCount = 7,
        body = """
            iget-object v0, p0, $adapterField
            if-nez v0, :has_adapter
            const/4 v0, -0x1
            return v0
            :has_adapter
            ${unpinnedLoop(n, "nop")}
            return v3
        """,
    )

    // Deletes every unpinned clip through Gboard's batch delete, then leaves edit mode.
    // Returns how many were deleted, or -1 without an adapter. p0 = ClipboardKeyboard.
    addHelperMethodIfMissing(
        classType = CLIPBOARD_KEYBOARD,
        name = CLEAR_HELPER,
        parameterTypes = emptyList(),
        returnType = "I",
        accessFlags = AccessFlags.PUBLIC.value,
        registerCount = 8,
        body = """
            iget-object v0, p0, $adapterField
            if-nez v0, :has_adapter
            const/4 v0, -0x1
            return v0
            :has_adapter
            new-instance v6, $SPARSE_ARRAY
            invoke-direct {v6}, $SPARSE_ARRAY-><init>()V
            ${unpinnedLoop(n, "invoke-virtual {v6, v2, v4}, $SPARSE_ARRAY->put(ILjava/lang/Object;)V")}
            if-eqz v3, :finish
            const/4 v1, 0x1
            invoke-direct {p0, v6, v1}, $CLIPBOARD_KEYBOARD->${n.clipboardBatchDelete}(${SPARSE_ARRAY}Z)V
            sget-object v1, ${n.uiExecutor}->${n.uiExecutorField}:${n.uiExecutor}
            new-instance v2, ${n.closeEditRunnable}
            const/4 v4, ${n.closeEditRunnableCase}
            invoke-direct {v2, p0, v4}, ${n.closeEditRunnable}-><init>(Ljava/lang/Object;I)V
            invoke-virtual {v1, v2}, ${n.uiExecutor}->execute(Ljava/lang/Runnable;)V
            :finish
            return v3
        """,
    )

    val handleEvent = method(CLIPBOARD_KEYBOARD, n.clipboardHandleEvent, listOf(n.keyEvent), "Z")
    val call = RuntimeCallId.CLIPBOARD_CLEAR_ALL_RUNTIME_ON_LONG_PRESS
    if (handleEvent.alreadyCalls(RuntimeAbiCatalog.abi(call).reference)) return
    val first = handleEvent.implementation!!.instructions.first()
    val keyEvent = n.keyEvent
    // p0 = ClipboardKeyboard, p1 = key event. A LONG_PRESS carrying CLIPBOARD_BATCH_DELETE
    // only exists because of the SoftKeyDef hook, so this never swallows a stock event.
    handleEvent.addInstructionsWithLabels(
        0,
        """
            iget-object v0, p1, $keyEvent->${n.keyEventActionTypeField}:${n.actionType}
            sget-object v1, ${n.actionType}->${n.longPressField}:${n.actionType}
            if-ne v0, v1, :pixelboard_clear_all_skip
            iget-object v0, p1, $keyEvent->${n.keyEventKeyDataField}:[${n.keyData}
            if-eqz v0, :pixelboard_clear_all_skip
            array-length v1, v0
            if-eqz v1, :pixelboard_clear_all_skip
            const/4 v1, 0x0
            aget-object v0, v0, v1
            if-eqz v0, :pixelboard_clear_all_skip
            iget v0, v0, ${n.keyData}->${n.keyDataKeycodeField}:I
            const v1, $BATCH_DELETE_KEYCODE
            if-ne v0, v1, :pixelboard_clear_all_skip
            iget-object v0, p0, $CLIPBOARD_KEYBOARD->${n.clipboardListField}:$RECYCLER_VIEW
            ${RuntimeCallEmitter.invoke(call, "p0, v0")}
            const/4 v0, 0x1
            return v0
        """.trimIndent(),
        ExternalLabel("pixelboard_clear_all_skip", first),
    )
}
