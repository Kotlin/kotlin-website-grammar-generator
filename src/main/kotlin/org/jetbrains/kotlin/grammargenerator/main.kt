package org.jetbrains.kotlin.grammargenerator

import com.xenomachina.argparser.ArgParser

fun main(args: Array<String>) {
    val parser = ArgParser(args)
    val lexerGrammarFile by parser.storing("-l", "--lexerFile", help = "path to ANTLR 4 lexer grammar file (.g4)")
    val parserGrammarFile by parser.storing("-p", "--parserFile", help = "path to ANTLR 4 parser grammar file (.g4)")
    val convertType by parser.mapping("--text" to ConvertType.TEXT, "--xml" to ConvertType.XML, help = "convert type (--text or --xml)")
    val outputFile by parser.storing("-o", "--output", help = "path to converted file")

    Runner.run(lexerGrammarFile, parserGrammarFile, convertType, outputFile)
}
