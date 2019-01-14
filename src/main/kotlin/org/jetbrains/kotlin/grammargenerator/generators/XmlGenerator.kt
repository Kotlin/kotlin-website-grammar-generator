package org.jetbrains.kotlin.grammargenerator.generators

import org.antlr.v4.tool.Rule
import org.antlr.v4.tool.ast.GrammarAST
import org.antlr.v4.tool.ast.RuleRefAST
import org.antlr.v4.tool.ast.TerminalAST
import org.jdom2.output.Format
import org.jdom2.output.XMLOutputter
import org.jetbrains.kotlin.grammargenerator.generators.Generator.Companion.LENGTH_FOR_RULE_SPLIT
import org.jetbrains.kotlin.grammargenerator.generators.Generator.Companion.rootNodes
import org.jetbrains.kotlin.grammargenerator.visitors.GrammarVisitor
import org.jonnyzzz.kotlin.xml.dsl.XWriter
import org.jonnyzzz.kotlin.xml.dsl.jdom.jdom
import java.io.File
import java.util.regex.Pattern

enum class GeneratorType { LEXER, PARSER }

data class ElementRenderResult(
        val contentLength: Int,
        val sectionName: String? = null,
        val buildElement: XWriter.() -> Unit
)

private typealias IXmlGenerator = Generator<ElementRenderResult, XWriter>

class XmlGenerator(
        override val lexerRules: Map<String, Rule>,
        override val parserRules: Map<String, Rule>,
        private val lexerGrammarFileLines: List<String>,
        private val parserGrammarFileLines: List<String>
) : IXmlGenerator {
    companion object {
        const val SECTION_DECLARATION_OFFSET = 3
        const val DOCS_FOLDER = "docs"
        private val sectionPattern = Pattern.compile("""^// SECTION: (?<section>[ \w]*?)$""")
    }

    override val usedLexerRules = mutableSetOf<String>()
    override lateinit var currentMode: GeneratorType

    private var currentRule: String? = null
    private val usagesMap = mutableMapOf<String, Pair<XWriter?, MutableSet<String>>>()

    private fun getVisitedRules(rules: Map<String, Rule>, visitor: GrammarVisitor) =
            rules.entries.associate { (ruleName, rule) ->
                ruleName to Pair(rule, rule.ast.visit(visitor) as ElementRenderResult)
            }

    private fun addGreedyMarker(isGreedy: Boolean) = ElementRenderResult(if (isGreedy) 0 else 1) {
        if (!isGreedy) {
            element("symbol") { cdata("?") }
        }
    }

    private fun joinThroughLength(
            elements: List<ElementRenderResult>
    ) = ElementRenderResult(elements.sumBy { (elementTextLength, _) -> elementTextLength }) {
        var bufferSize = 0

        elements.forEachIndexed { index, (elementTextLength, _, buildElement) ->
            if (index != 0) {
                bufferSize += elementTextLength
                if (bufferSize > LENGTH_FOR_RULE_SPLIT) {
                    element("crlf")
                    bufferSize = 0
                }
                element("whiteSpace")
            }
            buildElement()
        }
    }

    private fun groupUsingPipe(
            elements: List<ElementRenderResult>,
            groupingBracketsNeed: Boolean
    ) = ElementRenderResult(elements.sumBy { (elementTextLength, _) -> elementTextLength }) {
        if (groupingBracketsNeed)
            element("symbol") { cdata("(") }

        elements.forEachIndexed { index, (_, _, buildElement) ->
            if (index != 0) {
                if (groupingBracketsNeed) element("whiteSpace") else element("crlf")
                element("symbol") { cdata("|") }
                element("whiteSpace")
            }
            buildElement()
        }

        if (groupingBracketsNeed)
            element("symbol") { cdata(")") }
    }

    private fun getSectionDeclaration(ruleLineNumber: Int): String? {
        val targetGrammarFileLines = if (currentMode == GeneratorType.LEXER) lexerGrammarFileLines else parserGrammarFileLines
        val potentialSectionDeclarationLine = targetGrammarFileLines.getOrNull(ruleLineNumber - SECTION_DECLARATION_OFFSET) ?: return null

        return sectionPattern.matcher(potentialSectionDeclarationLine).let {
            if (it.find()) it.group("section") else null
        }
    }

    private fun runInContextBySectionInfo(
            rootContext: XWriter,
            currentContext: XWriter,
            sectionName: String?,
            builder: XWriter.() -> Unit
    ) {
        if (sectionName != null) {
            rootContext.element("set") {
                addDoc(sectionName)
                builder()
            }
        } else builder(currentContext)
    }

    private fun XWriter.addDoc(docName: String) {
        val sectionDocFile = File("$DOCS_FOLDER/$docName.txt")

        if (sectionDocFile.exists()) {
            element("doc") {
                cdata(sectionDocFile.readText())
            }
        }
    }

    private fun XWriter.generateRules(rules: Map<String, Pair<Rule, ElementRenderResult>>) {
        var currentContext = this

        rules.forEach { (_, ruleInfo) ->
            val (rule, result) = ruleInfo
            val (_, sectionName, buildElement) = result

            runInContextBySectionInfo(this, currentContext, sectionName) {
                if (this != currentContext)
                    currentContext = this

                element("item") {
                    if (rule.isFragment) {
                        element("annotation") {
                            text("helper")
                        }
                    }
                    buildElement()
                }
            }
        }
    }

    override fun optional(child: ElementRenderResult, isGreedy: Boolean): ElementRenderResult {
        val (greedyMarkerLength, _, addGreedyMarker) = addGreedyMarker(isGreedy)
        val (childContentLength, _, buildChild) = child

        return ElementRenderResult(childContentLength + 1 + greedyMarkerLength) {
            buildChild()
            element("symbol") { cdata("?") }
            addGreedyMarker()
        }
    }

    override fun plus(child: ElementRenderResult, isGreedy: Boolean): ElementRenderResult {
        val (greedyMarkerLength, _, addGreedyMarker) = addGreedyMarker(isGreedy)
        val (childContentLength, _, buildChild) = child

        return ElementRenderResult(childContentLength + 1 + greedyMarkerLength) {
            buildChild()
            element("symbol") { cdata("+") }
            addGreedyMarker()
        }
    }

    override fun star(child: ElementRenderResult, isGreedy: Boolean): ElementRenderResult {
        val (greedyMarkerLength, _, addGreedyMarker) = addGreedyMarker(isGreedy)
        val (childContentLength, _, buildChild) = child

        return ElementRenderResult(childContentLength + 1 + greedyMarkerLength) {
            buildChild()
            element("symbol") { cdata("*") }
            addGreedyMarker()
        }
    }

    override fun not(child: ElementRenderResult): ElementRenderResult {
        val (childContentLength, _, buildChild) = child

        return ElementRenderResult(childContentLength + 1) {
            element("symbol") { cdata("~") }
            buildChild()
        }
    }

    override fun range(childLeft: ElementRenderResult, childRight: ElementRenderResult): ElementRenderResult {
        val (childLeftContentLength, _, buildChildLeft) = childLeft
        val (childRightContentLength, _, buildChildRight) = childRight

        return ElementRenderResult(childLeftContentLength + childRightContentLength + 2) {
            buildChildLeft()
            element("string") { cdata("..") }
            buildChildRight()
        }
    }

    override fun rule(children: List<ElementRenderResult>, ruleName: String, lineNumber: Int) = ElementRenderResult(children.sumBy { it.contentLength }, getSectionDeclaration(lineNumber)) {
        currentRule = ruleName

        usagesMap[ruleName] = Pair(this, usagesMap[ruleName]?.second ?: mutableSetOf())

        if (rootNodes.contains(ruleName)) {
            element("annotation") {
                text("start")
            }
        }
        element("declaration") {
            attribute("name", ruleName)
        }
        element("description") {
            element("whitespace")
            element("whitespace")
            element("symbol") { cdata(":") }
            element("whitespace")
            children.forEach {
                (_, _, buildElement) -> buildElement()
            }
            element("crlf")
            element("whitespace")
            element("whitespace")
            element("other") { text(";") }
        }
    }

    override fun block(groupingBracketsNeed: Boolean, children: List<ElementRenderResult>) = groupUsingPipe(children, groupingBracketsNeed)

    override fun set(groupingBracketsNeed: Boolean, children: List<ElementRenderResult>) = groupUsingPipe(children, groupingBracketsNeed)

    override fun alt(children: List<ElementRenderResult>) = joinThroughLength(children)

    override fun root() = ElementRenderResult(0) {}

    override fun pred() = ElementRenderResult(0) {}

    override fun ruleRef(node: RuleRefAST) = ElementRenderResult(node.text.length) {
        element("identifier") {
            attribute("name", node.text)
        }
        usagesMap.putIfAbsent(node.text, Pair(null, mutableSetOf()))
        usagesMap[node.text]?.second?.add(currentRule!!)
    }

    override fun charsSet(node: GrammarAST) = ElementRenderResult(node.text.length) {
        element("symbol") { cdata ("[") }
        element("string") { cdata (node.text) }
        element("symbol") { cdata ("]") }
    }

    override fun terminal(node: TerminalAST): ElementRenderResult {
        val lexerRule = getLexerRule(node)

        return (lexerRule ?: node.text).let { nodeText ->
            ElementRenderResult(nodeText.length) {
                if (lexerRule == null) {
                    usagesMap.computeIfAbsent(node.text) { Pair(null, mutableSetOf()) }
                    usagesMap[node.text]?.second?.add(currentRule!!)
                }
                element("string") { cdata(nodeText) }
            }
        }
    }

    override fun run(builder: IXmlGenerator.(XWriter) -> Unit): String =
            XMLOutputter(Format.getPrettyFormat()).outputString(
                    jdom("tokens") { builder(this) }
            )

    override fun XWriter.generateNotationDescription() {
        element("set") {
            addDoc("notation")
        }
    }

    override fun XWriter.generateLexerRules(visitor: GrammarVisitor) {
        currentMode = GeneratorType.LEXER

        generateRules(rules = filterLexerRules(getVisitedRules(lexerRules, visitor), usedLexerRules))
    }

    override fun XWriter.generateParserRules(visitor: GrammarVisitor) {
        currentMode = GeneratorType.PARSER

        generateRules(rules = getVisitedRules(parserRules, visitor))

        usagesMap.values.filter { (_, usages) -> usages.isNotEmpty() }.forEach { (xwriter, usages) ->
            xwriter?.element("usages") {
                usages.forEach {
                    element("declaration") { text(it) }
                }
            }
        }
    }
}