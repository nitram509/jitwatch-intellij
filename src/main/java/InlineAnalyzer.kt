package ru.yole.jitwatch

import org.adoptopenjdk.jitwatch.core.JITWatchConstants.*
import org.adoptopenjdk.jitwatch.journal.AbstractJournalVisitable
import org.adoptopenjdk.jitwatch.journal.JournalUtil
import org.adoptopenjdk.jitwatch.model.IMetaMember
import org.adoptopenjdk.jitwatch.model.IParseDictionary
import org.adoptopenjdk.jitwatch.model.IReadOnlyJITDataModel
import org.adoptopenjdk.jitwatch.model.Tag
import org.adoptopenjdk.jitwatch.treevisitor.ITreeVisitable
import org.adoptopenjdk.jitwatch.util.ParseUtil

class InlineFailureInfo(val callSite: IMetaMember, val bci: Int, val callee: IMetaMember, val reason: String)

class InlineAnalyzer(val model: IReadOnlyJITDataModel, val filter: (IMetaMember) -> Boolean) : ITreeVisitable {
    private val _failures = mutableListOf<InlineFailureInfo>()

    val failures: List<InlineFailureInfo>
        get() = _failures

    override fun visit(mm: IMetaMember?) {
        if (mm == null || !mm.isCompiled) return
        JournalUtil.visitParseTagsOfLastTask(mm.journal, InlineJournalVisitor(model, mm, _failures, filter))
    }

    override fun reset() {
    }

    private class InlineJournalVisitor(val model: IReadOnlyJITDataModel,
                                       val callSite: IMetaMember,
                                       val failures: MutableList<InlineFailureInfo>,
                                       val filter: (IMetaMember) -> Boolean) : AbstractJournalVisitable() {
        override fun visitTag(toVisit: Tag, parseDictionary: IParseDictionary) {
            processParseTag(toVisit, parseDictionary)
        }

        private fun processParseTag(toVisit: Tag, parseDictionary: IParseDictionary, bci: Int? = null) {
            var methodID: String? = null
            var currentBCI : Int = 0

            for (child in toVisit.children) {
                val tagName = child.name

                val tagAttrs = child.attributes

                when (tagName) {
                    TAG_METHOD -> methodID = tagAttrs[ATTR_ID]

                    TAG_BC -> {
                        val newBCI = tagAttrs[ATTR_BCI]
                        if (newBCI != null) {
                            currentBCI = newBCI.toInt()
                        }
                    }

                    TAG_CALL -> {
                        methodID = tagAttrs[ATTR_METHOD]
                    }

                    TAG_INLINE_FAIL -> {
                        val reason = tagAttrs[ATTR_REASON]

                        val metaMember = ParseUtil.lookupMember(methodID, parseDictionary, model)
                        if (metaMember != null && filter(metaMember)) {
                            failures.add(InlineFailureInfo(callSite, bci ?: currentBCI, metaMember, reason ?: "Unknown"))
                        }

                        methodID = null
                    }

                    TAG_PARSE -> {
                        processParseTag(child, parseDictionary, bci ?: currentBCI)
                    }

                    TAG_PHASE -> {
                        val phaseName = tagAttrs.get(ATTR_NAME)

                        if (S_PARSE_HIR == phaseName) {
                            visitTag(child, parseDictionary)
                        }
                    }

                    else -> handleOther(child)
                }
            }
        }
    }
}