package dev.voqal.assistant.tool.text

import com.aallam.openai.api.chat.Tool
import com.intellij.codeInsight.CodeSmellInfo
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.fragments.DiffFragmentImpl
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.simple.SimpleDiffChange
import com.intellij.diff.tools.util.base.TextDiffSettingsHolder
import com.intellij.diff.util.DiffUtil
import com.intellij.lang.Language
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.*
import com.intellij.openapi.vcs.CodeSmellDetector
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.suggested.range
import dev.voqal.assistant.VoqalDirective
import dev.voqal.assistant.processing.TextSearcher
import dev.voqal.assistant.tool.VoqalTool
import dev.voqal.assistant.tool.system.CancelTool
import dev.voqal.assistant.tool.system.LooksGoodTool
import dev.voqal.services.*
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.Pair
import kotlin.math.abs

class EditTextTool : VoqalTool() {

    companion object {
        const val NAME = "edit_text"

        val VOQAL_HIGHLIGHTERS = Key.create<List<RangeHighlighter>>("VOQAL_HIGHLIGHTERS")

        //diff edit format, each line must start with -num| or +num| where num is the line number
        private val diffRegex = Regex("^([\\s-+])?(\\d+)\\|(.*)$")

        internal const val STREAM_INDICATOR_LAYER = 6099
        internal const val ACTIVE_EDIT_LAYER = 6100

        private val SMART_RENAME_ELEMENT = Key.create<PsiElement>("SMART_RENAME_ELEMENT")
        private val ORIGINAL_NAME = Key.create<String>("ORIGINAL_NAME")
        private val NEW_NAME = Key.create<String>("NEW_NAME")
    }

    override val name = NAME

    override suspend fun actionPerformed(args: JsonObject, directive: VoqalDirective) {
        val project = directive.project
        val log = project.getVoqalLogger(this::class)
        log.debug("Triggering edit text")

        val editor = directive.ide.editor!!
        var responseCode = args.getString("text")
        if (responseCode.isBlank()) {
            log.debug("Ignoring empty edit text")
            return
        }
        log.trace("Got completion: ${responseCode.replace("\n", "\\n")}")
        responseCode = responseCode.replace("↕", "") //remove any carets

        //check for vui interactions
        if (TextSearcher.checkForVuiInteraction("cancel", responseCode)) {
            log.debug("Cancelling editing")
            project.service<VoqalToolService>().blindExecute(CancelTool())
            return
        } else if (TextSearcher.checkForVuiInteraction("accept", responseCode)) {
            log.debug("Accepting editing")
            project.service<VoqalToolService>().blindExecute(LooksGoodTool())
            return
        }

        log.debug("Doing editing")
        project.service<VoqalMemoryService>().saveEditLabel(directive.internal.memorySlice.id, editor)
        val streaming = args.getBoolean("streaming") ?: false
        val editHighlighters = doDocumentEdits(project, responseCode, editor, streaming)
        val updatedHighlighters = (editor.getUserData(VOQAL_HIGHLIGHTERS) ?: emptyList()) + editHighlighters
        editor.putUserData(VOQAL_HIGHLIGHTERS, updatedHighlighters)
        WriteCommandAction.writeCommandAction(project).compute(ThrowableComputable {
            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        })

        if (!streaming) {
            PsiDocumentManager.getInstance(project).performForCommittedDocument(editor.document) {
                //move caret to end of last highlight
                val lastHighlight = editHighlighters.lastOrNull()
                val caretOffset = lastHighlight?.range?.endOffset
                if (caretOffset != null) {
                    ApplicationManager.getApplication().invokeAndWait {
                        editor.caretModel.moveToOffset(caretOffset)

                        var visibleRange: ProperTextRange? = null
                        ApplicationManager.getApplication().invokeAndWait {
                            visibleRange = editor.calculateVisibleRange()
                        }
                        val anyEditVisible = editHighlighters.any { visibleRange!!.intersects(it.range!!) }

                        //determine if caret is visible and scroll if necessary
                        val visibleRectangle = editor.scrollingModel.visibleArea
                        val caretRectangle = editor.logicalPositionToXY(editor.offsetToLogicalPosition(caretOffset))
                        if (!anyEditVisible && !visibleRectangle.contains(caretRectangle)) {
                            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
                        }
                    }
                }

                if (directive.internal.promptSettings?.codeSmellCorrection == true) {
                    log.debug("Checking for code smells")
                    checkCodeSmells(directive)
                } else {
                    log.debug("Skipping code smell check")
                    project.service<VoqalStatusService>()
                        .updateText("Finished editing file: " + directive.developer.viewingFile?.name)
                }
            }
        }
    }

    private fun checkCodeSmells(directive: VoqalDirective) {
        val project = directive.project
        project.invokeLater {
            val editor = directive.ide.editor!!
            val codeSmells = mutableListOf<CodeSmellInfo>()
            val virtualFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)?.virtualFile
            codeSmells.addAll(CodeSmellDetector.getInstance(project).findCodeSmells(listOf(virtualFile))
                .filter { it.severity == HighlightSeverity.ERROR })

            project.scope.launch {
                if (codeSmells.isNotEmpty()) {
                    project.service<VoqalStatusService>().updateText("Found " + codeSmells.size + " code smells")
                    val correctionDirective = directive.copy(
                        developer = directive.developer.copy(
                            transcription = "The following code smells were detected:\n -" +
                                    codeSmells.joinToString("\n -") { it.description }
                        )
                    ) //todo: throw VoqalCritique
                    project.service<VoqalDirectiveService>().executeDirective(correctionDirective)
                } else {
                    project.service<VoqalStatusService>()
                        .updateText("Finished editing file: " + directive.developer.viewingFile?.name)
                }
            }
        }
    }

    suspend fun doDocumentEdits(
        project: Project,
        replaceResponseCode: String,
        editor: Editor,
        streaming: Boolean = false,
        originalText: String? = null
    ): List<RangeHighlighter> {
        var responseCode = removeDiffHeaderIfPresent(replaceResponseCode)

        //remove existing stream indicator (if present)
        val streamIndicators = mutableListOf<RangeHighlighter>()
        val existingHighlighters = editor.getUserData(VOQAL_HIGHLIGHTERS)?.toMutableList()
        val previousStreamIndicator = existingHighlighters?.find { it.layer == STREAM_INDICATOR_LAYER }
        if (previousStreamIndicator != null) {
            editor.markupModel.removeHighlighter(previousStreamIndicator)
            editor.putUserData(VOQAL_HIGHLIGHTERS, existingHighlighters.apply {
                remove(previousStreamIndicator)
            })
        }

        if (streaming) {
            val fullTextWithEdits = getFullTextAfterStreamEdits(
                responseCode, originalText,
                editor, project,
                previousStreamIndicator, streamIndicators
            )
            if (fullTextWithEdits != null) {
                responseCode = fullTextWithEdits
            } else {
                return streamIndicators
            }
        }

        val editHighlighters = if (responseCode.lines().filter { it.isNotBlank() }.all { diffRegex.matches(it) }) {
            doDiffTextEdit(responseCode, editor, project)
        } else {
            doFullTextEdit(editor, responseCode, project)
        }
        return streamIndicators + editHighlighters.sortedBy { it.startOffset }
    }

    private fun getFullTextAfterStreamEdits(
        responseCode: String,
        originalText: String?,
        editor: Editor,
        project: Project,
        previousStreamIndicator: RangeHighlighter?,
        streamIndicators: MutableList<RangeHighlighter>
    ): String? {
        val log = project.getVoqalLogger(this::class)
        val existingHighlighters = editor.getUserData(VOQAL_HIGHLIGHTERS)?.toMutableList()

        //when streaming, the last line may or may not be complete, drop to be safe
        var fullTextWithEdits = responseCode
        fullTextWithEdits = fullTextWithEdits.lines().dropLast(1).joinToString("\n")

        //determine diff between original text and text streamed so far
        var origText = originalText ?: editor.document.text
        val highlighter = project.service<VoqalMemoryService>()
            .getUserData("visibleRangeHighlighter") as RangeHighlighter?
        val visibleRange = highlighter?.range
        if (visibleRange != null) {
            ApplicationManager.getApplication().invokeAndWait {
                origText = visibleRange.substring(editor.document.text)
            }
        }
        val simpleDiffs = getSimpleDiffChanges(origText, fullTextWithEdits, project)
        val lastDiff = simpleDiffs.lastOrNull()
        val textRange = lastDiff?.fragment?.let { TextRange(it.startOffset2, it.endOffset2) }

        //append remaining original text to text streamed so far (assuming no changes)
        if (textRange != null) {
            val diffFragments = simpleDiffs.toMutableList().apply { removeLast() }.map { it.fragment }
            var diffOffset = 0
            for (fragment in diffFragments) {
                val length1 = fragment.endOffset1 - fragment.startOffset1
                val length2 = fragment.endOffset2 - fragment.startOffset2
                diffOffset += length1 - length2
            }

            val previousIndicatorLine = previousStreamIndicator?.startOffset?.let {
                editor.document.getLineNumber(it)
            } ?: 0
            val visibleRangeLineOffset = visibleRange?.startOffset ?: 0
            val linesWithEdits = diffFragments.map {
                editor.document.getLineNumber(it.startOffset1 + visibleRangeLineOffset)
            }
            var indicatorLine = editor.document.getLineNumber(textRange.endOffset + visibleRangeLineOffset)

            //contains modification instead of addition on last change, can not append remaining original text
            if (!isAppendRemainingChange(origText, lastDiff, visibleRange)) {
                streamIndicators.add(createStreamIndicator(editor, previousIndicatorLine))
                return null
            }

            //wait for changes to be applied on edited lines before progressing stream indicator
            val hasEditedLineInRange = linesWithEdits.any { it in (previousIndicatorLine + 1) until indicatorLine }
            if (hasEditedLineInRange) {
                indicatorLine = existingHighlighters?.filter { it.layer == ACTIVE_EDIT_LAYER }
                    ?.maxOfOrNull { editor.document.getLineNumber(it.range!!.endOffset) }
                    ?: (linesWithEdits.filter { it < indicatorLine }.maxOrNull() ?: indicatorLine)
                if (indicatorLine < previousIndicatorLine) {
                    indicatorLine = previousIndicatorLine
                }
            }

            if (textRange.endOffset < origText.length) {
                fullTextWithEdits += origText.substring(textRange.endOffset + diffOffset)
            } else {
                //edit is adding text past visible range, need to re-add final line dropped above
                var finalLine = responseCode.lines().last()
                if (finalLine.isEmpty()) {
                    finalLine = "\n"
                }
                fullTextWithEdits += finalLine
            }
            streamIndicators.add(createStreamIndicator(editor, indicatorLine))
        } else {
            log.warn("Could not find existing text in editor to update stream indicator")
        }
        return fullTextWithEdits
    }

    private suspend fun doDiffTextEdit(
        replaceResponseCode: String,
        editor: Editor,
        project: Project
    ): MutableList<RangeHighlighter> {
        val diffs = replaceResponseCode.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val match = diffRegex.matchEntire(line)
            if (match != null) {
                val type = match.groupValues[1]
                val lineNum = match.groupValues[2].toInt()
                val text = match.groupValues[3]
                Pair(type, lineNum to text)
            } else null
        }

        val finalText = editor.document.text.lines().toMutableList()

        //create a map of line changes, with the line number as the key
        val lineChanges = mutableMapOf<Int, MutableList<Pair<String, String>>>()
        diffs.forEach { diff ->
            val (type, lineData) = diff
            val (lineNum, text) = lineData
            lineChanges.computeIfAbsent(lineNum) { mutableListOf() }.add(type to text)
        }

        //first handle removals, then handle additions
        lineChanges.keys.sortedDescending().forEach { lineNum ->
            val changes = lineChanges[lineNum]!!
            changes.forEach { (type, _) ->
                if (type == "-") {
                    finalText.removeAt(lineNum - 1)
                }
            }
            changes.forEach { (type, text) ->
                if (type == "+") {
                    finalText.add(lineNum - 1, text)
                }
            }
        }

        val finalTextString = finalText.joinToString("\n")
        return doFullTextEdit(editor, finalTextString, project)
    }

    private suspend fun doFullTextEdit(
        editor: Editor,
        replaceResponseCode: String,
        project: Project
    ): MutableList<RangeHighlighter> {
        val log = project.getVoqalLogger(this::class)

        //get all diffs from current code to completion
        var oldText = editor.document.text
        var newText = replaceResponseCode

        //find smallest way to modify text to desired completion
        val result = coroutineScope {
            val diffList = mutableListOf<Deferred<Diff?>>()
            val fullTextDiff = async { getTextDiff(project, oldText, newText) }
            diffList.add(fullTextDiff)

            val visibleTextDiff = async { getVisibleTextDiff(project, editor, newText) }
            diffList.add(visibleTextDiff)

            val indentedVisibleTextDiff = async {
                getVisibleTextDiff(project, editor, newText, true)
            }
            diffList.add(indentedVisibleTextDiff)

            val commonIndentedVisibleTextDiff = async {
                getVisibleTextDiff(project, editor, newText, true, true)
            }
            diffList.add(commonIndentedVisibleTextDiff)

            diffList.map { it.await() }
        }
        val fullTextDiff = result[0]!!
        var smallestDiff = fullTextDiff
        var diffFragments = fullTextDiff.fragments
        var updatedOldText = oldText
        var updatedNewText = newText
        var diffType: String
        result.filterNotNull().forEach { diff ->
            val fewerChanges = diff.diffAmount == smallestDiff.diffAmount && diff.fragments.size < diffFragments.size
            if (diff.diffAmount < smallestDiff.diffAmount || fewerChanges) {
                smallestDiff = diff
                diffFragments = diff.fragments
                updatedOldText = diff.originalText
                updatedNewText = diff.newText
                diffType = diff.diffType
            }
        }
        diffFragments = smallestDiff.fragments
        oldText = updatedOldText
        newText = updatedNewText
        diffType = smallestDiff.diffType
        log.debug("Smallest diff: $diffType")

        val offsets = mutableListOf<Pair<Int, Int>>()
        val activeHighlighters = mutableListOf<RangeHighlighter>()
        diffFragments.forEach { diff ->
            var diffStartOffset = diff.startOffset1
            var diffEndOffset = diff.endOffset1
            offsets.sortedBy { it.first }.forEach {
                if (it.first < diff.startOffset1 && (diff.startOffset1 - diffStartOffset) + it.first != diff.startOffset1) {
                    diffStartOffset += it.second
                }
                if (it.first < diff.endOffset1) {
                    diffEndOffset += it.second
                }
            }

            var element = ReadAction.compute(ThrowableComputable {
                PsiDocumentManager.getInstance(project).getPsiFile(editor.document)?.findElementAt(diffStartOffset)
            })
            val isIdentifier = element?.let { project.service<VoqalSearchService>().isIdentifier(it) } ?: false
            val parent = if (isIdentifier) ReadAction.compute(ThrowableComputable { element?.parent }) else null
            if (isIdentifier && parent is PsiNamedElement) {
                //can be smart renamed, use rename processor
                element = parent
                val text1 = TextRange(diffStartOffset, diffEndOffset).substring(fullTextDiff.originalText)
                val text2 = TextRange(diff.startOffset2, diff.endOffset2).substring(newText)
                val validName = isValidIdentifier(element.language, text2)

                val renameOffset = text2.length - text1.length
                offsets.add(Pair(diffStartOffset, renameOffset))
                if (text1.isNotEmpty() && validName) {
                    log.debug("Renaming element: $text1 -> $text2")
                    val renameProcessor = ReadAction.compute(ThrowableComputable {
                        RenameProcessor(project, element, text2, element.useScope, false, true)
                    })
                    val usageInfos = ProgressManager.getInstance().computeInNonCancelableSection(ThrowableComputable {
                        ReadAction.compute(ThrowableComputable { renameProcessor.findUsages() })
                    })
                    WriteCommandAction.writeCommandAction(project).compute(ThrowableComputable {
                        renameProcessor.executeEx(usageInfos)
                    })
                    ReadAction.compute(ThrowableComputable {
                        usageInfos.forEach {
                            val navigationRange = it.navigationRange
                            offsets.add(Pair(navigationRange.startOffset, renameOffset))

                            val newTextRange = TextRange(navigationRange.startOffset, navigationRange.endOffset)
                            val textAttributes = TextAttributes()
                            textAttributes.backgroundColor = EditorColorsManager.getInstance()
                                .globalScheme.defaultBackground.darker()
                            val highlighter = editor.markupModel.addRangeHighlighter(
                                newTextRange.startOffset, newTextRange.endOffset,
                                ACTIVE_EDIT_LAYER, textAttributes, HighlighterTargetArea.EXACT_RANGE
                            )
                            activeHighlighters.add(highlighter)
                        }
                    })
                } else {
                    //otherwise, just replace text
                    log.debug("Replacing text: $text1 -> $text2")
                    WriteCommandAction.writeCommandAction(project).compute(ThrowableComputable {
                        editor.document.replaceString(diffStartOffset, diffEndOffset, text2)
                    })
                }

                val newTextRange = TextRange(diffStartOffset, diffStartOffset + text2.length)
                val textAttributes = TextAttributes()
                textAttributes.backgroundColor = EditorColorsManager.getInstance()
                    .globalScheme.defaultBackground.darker()
                val highlighter = editor.markupModel.addRangeHighlighter(
                    newTextRange.startOffset, newTextRange.endOffset,
                    ACTIVE_EDIT_LAYER, textAttributes, HighlighterTargetArea.EXACT_RANGE
                )
                highlighter.putUserData(SMART_RENAME_ELEMENT, element)
                highlighter.putUserData(ORIGINAL_NAME, text1)
                highlighter.putUserData(NEW_NAME, text2)
                activeHighlighters.add(highlighter)
            } else {
                //otherwise, just replace text
                val text1 = TextRange(diffStartOffset, diffEndOffset).substring(editor.document.text)
                val text2 = TextRange(diff.startOffset2, diff.endOffset2).substring(newText)

                //first make sure this hasn't already been smart renamed
                if (parent is PsiReference) {
                    val declaration = ReadAction.compute(ThrowableComputable { parent.resolve() })
                    val allHighlighters = activeHighlighters + (editor.getUserData(VOQAL_HIGHLIGHTERS) ?: emptyList())
                    val smartRenameElement = allHighlighters.find {
                        it.getUserData(SMART_RENAME_ELEMENT) === declaration
                    }
                    if (smartRenameElement != null) {
                        val currentName = ReadAction.compute(ThrowableComputable { element?.text })
                        val originalName = smartRenameElement.getUserData(ORIGINAL_NAME)
                        val newName = smartRenameElement.getUserData(NEW_NAME)
                        if (text2 in setOf(originalName, newName) && currentName == newName) {
                            log.debug("Already smart renamed: $originalName -> $currentName")
                            return@forEach
                        }
                    }
                }

                val renameOffset = text2.length - text1.length
                offsets.add(Pair(diffStartOffset, renameOffset))

                log.debug("Replacing text: $text1 -> $text2")
                WriteCommandAction.writeCommandAction(project).compute(ThrowableComputable {
                    editor.document.replaceString(diffStartOffset, diffEndOffset, text2)
                })

                val newTextRange = TextRange(diffStartOffset, diffStartOffset + text2.length)
                val textAttributes = TextAttributes()
                textAttributes.backgroundColor = EditorColorsManager.getInstance()
                    .globalScheme.defaultBackground.darker()
                val highlighter = editor.markupModel.addRangeHighlighter(
                    newTextRange.startOffset, newTextRange.endOffset,
                    ACTIVE_EDIT_LAYER, textAttributes, HighlighterTargetArea.EXACT_RANGE
                )
                if (newTextRange.length > 0) {
                    activeHighlighters.add(highlighter)
                }
            }
        }
        return activeHighlighters
    }

    private fun getVisibleTextDiff(
        project: Project,
        editor: Editor,
        newText: String,
        indent: Boolean = false,
        replaceMinCommonIndent: Boolean = false
    ): Diff? {
        var oldText: String? = null
        val highlighter = project.service<VoqalMemoryService>()
            .getUserData("visibleRangeHighlighter") as RangeHighlighter?
        if (highlighter == null) {
            return null
        }
        val visibleRange = highlighter.range!!
        ApplicationManager.getApplication().invokeAndWait {
            oldText = visibleRange.substring(editor.document.text)
        }

        var newText = newText
        if (replaceMinCommonIndent) {
            //i.e. if oldText uses tab and newText uses 4 spaces, replace all leading 4 spaces with tab
            //first, detect indentation type and width used in the old text
            val oldTextIndent = oldText!!.lines()
                .filter(String::isNotBlank)
                .map { line ->
                    val match = Regex("^(\\s+)").find(line)
                    match?.value ?: ""
                }
                .minByOrNull { it.length } ?: ""

            if (oldTextIndent.isEmpty()) {
                return null
            }

            //then, detect the indentation type and width used in the new text
            var needsFinalOldIndent = false
            var newTextIndent = newText.lines()
                .filter(String::isNotBlank)
                .map { line ->
                    val match = Regex("^(\\s+)").find(line)
                    match?.value ?: ""
                }
                .minByOrNull { it.length } ?: ""
            if (newTextIndent.isEmpty()) {
                //old text may have been de-indented, so ignore lines that don't start with some kind of whitespace
                newTextIndent = newText.lines()
                    .filter { it.isNotEmpty() }
                    .mapNotNull { line ->
                        val match = Regex("^(\\s+)").find(line)
                        match?.value
                    }
                    .filter { it.length > 1 }
                    .minByOrNull { it.length } ?: ""

                if (newTextIndent.isEmpty()) {
                    return null
                }
                needsFinalOldIndent = true
            }

            //apply the detected indentation to each line of the new text
            val finalNewText = newText.lines().joinToString("\n") { line ->
                if (line.isBlank()) {
                    line
                } else {
                    val match = Regex("^(\\s+)").find(line)
                    val lineIndent = match?.value ?: ""
                    if (lineIndent.isEmpty() || lineIndent.length < newTextIndent.length) {
                        line
                    } else {
                        //replace new text min common indent with old text min common indent
                        var modifiedLine = line
                        var modifiedIndent = ""
                        while (modifiedLine.startsWith(newTextIndent)) {
                            modifiedIndent += oldTextIndent
                            modifiedLine = modifiedLine.substring(newTextIndent.length)
                        }
                        modifiedIndent + modifiedLine
                    }
                }
            }
            if (needsFinalOldIndent) {
                newText = finalNewText.lines().joinToString("\n") { line ->
                    if (line.isBlank()) line else oldTextIndent + line
                }
            } else {
                newText = finalNewText
            }
        } else if (indent) {
            //detect the indentation type and width used in the old text
            val oldTextIndent = oldText!!.lines()
                .filter(String::isNotBlank)
                .map { line ->
                    val match = Regex("^(\\s+)").find(line)
                    match?.value ?: ""
                }
                .minByOrNull { it.length } ?: ""
            if (oldTextIndent.isEmpty()) {
                return null
            }

            //apply the detected indentation to each line of the new text
            newText = newText.lines().joinToString("\n") { line ->
                if (line.isBlank()) line else oldTextIndent + line
            }
        }

        val textDiff = getTextDiff(project, oldText!!, newText)
        //update start offsets to be relative to full document
        val fragments = textDiff.fragments.map {
            DiffFragmentImpl(
                visibleRange.startOffset + it.startOffset1,
                visibleRange.startOffset + it.endOffset1,
                it.startOffset2,
                it.endOffset2
            )
        }.toMutableList()

        return Diff(oldText!!, fragments, newText, "visible:indent:$indent")
    }

    private fun getTextDiff(project: Project, oldText: String, newText: String): Diff {
        val changes = getSimpleDiffChanges(oldText, newText, project)
        val fragments = changes.map { Pair(it, it.fragment.innerFragments) }
            .flatMap { pair ->
                pair.second?.map {
                    DiffFragmentImpl(
                        pair.first.fragment.startOffset1 + it.startOffset1,
                        pair.first.fragment.startOffset1 + it.endOffset1,
                        pair.first.fragment.startOffset2 + it.startOffset2,
                        pair.first.fragment.startOffset2 + it.endOffset2
                    )
                } ?: listOf(
                    DiffFragmentImpl(
                        pair.first.fragment.startOffset1,
                        pair.first.fragment.endOffset1,
                        pair.first.fragment.startOffset2,
                        pair.first.fragment.endOffset2
                    )
                )
            }.toMutableList()

        return Diff(oldText, fragments, newText, "full")
    }

    private fun getSimpleDiffChanges(
        oldText: String,
        newText: String,
        project: Project
    ): List<SimpleDiffChange> {
        val disposable = Disposer.newDisposable()
        val oldContent = DiffContentFactory.getInstance().create(oldText)
        val newContent = DiffContentFactory.getInstance().create(newText)
        val provider = DiffUtil.createTextDiffProvider(
            project, SimpleDiffRequest("Voqal Diff", oldContent, newContent, "Old", "New"),
            TextDiffSettingsHolder.TextDiffSettings(), {}, disposable
        )
        val fragments = provider.compare(oldText, newText, EmptyProgressIndicator())
        Disposer.dispose(disposable)

        return fragments?.mapIndexed { index, fragment ->
            SimpleDiffChange(index, fragment)
        } ?: emptyList()
    }

    private data class Diff(
        val originalText: String,
        val fragments: List<DiffFragmentImpl>,
        val newText: String,
        val diffType: String
    ) {
        val diffAmount by lazy {
            var totalDiffAmount = 0
            for (fragment in fragments) {
                val length1 = fragment.endOffset1 - fragment.startOffset1
                val length2 = fragment.endOffset2 - fragment.startOffset2
                totalDiffAmount += abs(length1 - length2)
            }
            totalDiffAmount
        }
    }

    private fun createStreamIndicator(editor: Editor, lineNumber: Int): RangeHighlighter {
        return editor.markupModel.addRangeHighlighter(
            editor.document.getLineStartOffset(lineNumber),
            editor.document.getLineEndOffset(lineNumber),
            STREAM_INDICATOR_LAYER,
            TextAttributes().apply {
                backgroundColor = EditorColorsManager.getInstance()
                    .globalScheme.getColor(ColorKey.find("CARET_ROW_COLOR"))
            },
            HighlighterTargetArea.LINES_IN_RANGE
        )
    }

    private fun isAppendRemainingChange(
        originalText: String,
        diffChange: SimpleDiffChange,
        visibleRange: TextRange?
    ): Boolean {
        if (visibleRange != null) {
            val offsetVisibleRange = TextRange(0, visibleRange.endOffset - visibleRange.startOffset)
            val appendAfterVisibleRange = diffChange.fragment.endOffset1 == originalText.length
                    && diffChange.fragment.startOffset2 == offsetVisibleRange.endOffset
                    && diffChange.fragment.endOffset2 > diffChange.fragment.startOffset2
            if (appendAfterVisibleRange) {
                return true
            }
        }
        return diffChange.fragment.endOffset1 == originalText.length
                && abs(diffChange.fragment.startOffset2 - diffChange.fragment.endOffset2) == 0
    }

    private fun removeDiffHeaderIfPresent(responseCode: String): String {
        //todo: more robust diff header detection
        var finalResponseCode = responseCode
        if (
            finalResponseCode.lines().firstOrNull()?.startsWith("---") == true &&
            finalResponseCode.lines().getOrNull(1)?.startsWith("+++") == true &&
            finalResponseCode.lines().getOrNull(2)?.startsWith("@@") == true
        ) {
            finalResponseCode = finalResponseCode.lines().drop(3).joinToString("\n")
        }
        return finalResponseCode
    }

    //PsiNameHelper.getInstance(project).isIdentifier(newName)
    private fun isValidIdentifier(language: Language, text: String): Boolean {
        return text.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*")) //todo: per lang regex
    }

    override fun isVisible(directive: VoqalDirective): Boolean = false

    override fun asTool(directive: VoqalDirective): Tool {
        throw UnsupportedOperationException("Not supported")
    }
}
