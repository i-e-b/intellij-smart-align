package com.github.ttyniwa.intellij.plugin.align.aligner

import java.lang.IllegalArgumentException

class ResultLines(lineSize: Int) {
    private val lines = Array(lineSize) { "" }

    operator fun get(index: Int) = lines[index]

    operator fun set(index: Int, value: String) {
        lines[index] = value
    }

    fun toList() = lines.toList()

    fun findFurthestLength() = lines.map { it.length }.max()!!
}

object Aligner {
    fun align(text: String, anchor: Int): String {
        val alignTargetTokens = listOf(TokenType.Comma, TokenType.Colon, TokenType.Assign, TokenType.OneLineComment)
        val tokenLexers: List<TokenLexer> = listOf(
                // @formatter:off
                SimpleTokenLexer("+=", TokenType.Assign),
                SimpleTokenLexer("-=", TokenType.Assign),
                SimpleTokenLexer("*=", TokenType.Assign),
                SimpleTokenLexer("/=", TokenType.Assign),
                SimpleTokenLexer("=" , TokenType.Assign),
                SimpleTokenLexer("->", TokenType.Arrow),
                SimpleTokenLexer("=>", TokenType.Comparator),
                SimpleTokenLexer("=<", TokenType.Comparator),
                SimpleTokenLexer(">=", TokenType.Comparator),
                SimpleTokenLexer("<=", TokenType.Comparator),
                SimpleTokenLexer("::", TokenType.Operator),
                SimpleTokenLexer(":" , TokenType.Colon),
                SimpleTokenLexer("," , TokenType.Comma),
                SimpleTokenLexer("[" , TokenType.Bracket),
                SimpleTokenLexer("]" , TokenType.Bracket),
                SimpleTokenLexer("{" , TokenType.Bracket),
                SimpleTokenLexer("}" , TokenType.Bracket),
                SimpleTokenLexer("(" , TokenType.Bracket),
                SimpleTokenLexer(")" , TokenType.Bracket),
                StringTokenLexer("'"),
                StringTokenLexer("\""),
                OneLineCommentTokenLexer("//"),
                MultiLineCommentTokenLexer("/*", "*/")
                // @formatter:on
        )

        val lineSeparator = findLineSeparator(text)
        val rawLines = text.split(lineSeparator)

        val alignLines = detectLinesToAlign(rawLines, anchor, alignTargetTokens, tokenLexers)
        val formattedLines = align(alignLines, alignTargetTokens)

        return listOf(
                rawLines.subList(0, alignLines.start),
                formattedLines.toList(),
                rawLines.subList(alignLines.end + 1, rawLines.size)
        )
                .flatten()
                .joinToString(lineSeparator)
    }

    private fun align(lineRange: LineRange, alignTargetTokens: List<TokenType>): ResultLines {
        // option
        val isPaddingTokenRight = true

        //
        // Remove whitespace around [alignTargetTokens]
        lineRange.lines.forEach { line -> line.trim(alignTargetTokens) }

        //
        // Align
        val resultLines = ResultLines(lineRange.size)
        val alignedTokenIndexes = IntArray(lineRange.size) { -1 }
        val isCodeAlignCompleted = BooleanArray(lineRange.size) { false }

        // Loop for each token to be aligned.
        do {
            var didProcess = false

            //
            // Joins the string before the token to be aligned to [resultLines].
            lineRange.lines.forEachIndexed { i, line ->
                if (isCodeAlignCompleted[i]) return@forEachIndexed

                val alignTokenIndex = line.indexOf(alignTargetTokens, alignedTokenIndexes[i] + 1)
                val alignToken = line.tokens.getOrNull(alignTokenIndex)

                if (alignToken != null) { // token found.
                    resultLines[i] += line.getRawTextBetween(alignedTokenIndexes[i] + 1, alignTokenIndex)
                    if (alignToken.type == TokenType.OneLineComment) {
                        isCodeAlignCompleted[i] = true
                    }
                    alignedTokenIndexes[i] = alignTokenIndex - 1
                } else { // token not found.
                    resultLines[i] += line.getRawTextBetween(alignedTokenIndexes[i] + 1, line.tokens.size)
                    isCodeAlignCompleted[i] = true
                    alignedTokenIndexes[i] = line.tokens.size - 1
                }
            }

            //
            // find furthest line length
            val furthestLength = resultLines.findFurthestLength()

            val longestOperatorLength = lineRange.lines
                    .mapIndexed { i, line -> line.tokens.getOrNull(alignedTokenIndexes[i] + 1)?.text?.length }
                    .filterIndexed { i, _ -> !isCodeAlignCompleted[i] }
                    .filterNotNull()
                    .max()

            //
            // align token
            lineRange.lines.forEachIndexed { i, line ->
                if (isCodeAlignCompleted[i]) return@forEachIndexed

                val currentToken = line.tokens[alignedTokenIndexes[i] + 1]
                val nextToken = line.tokens.getOrNull(alignedTokenIndexes[i] + 2)
                val paddingNum = furthestLength - resultLines[i].length
                val tokenRightPadding = if (isPaddingTokenRight) {
                    " ".repeat(longestOperatorLength!! - currentToken.text.length)
                } else {
                    ""
                }

                if (currentToken.type in listOf(TokenType.Assign, TokenType.Arrow)) {
                    resultLines[i] += " ".repeat(paddingNum) + " " + tokenRightPadding + currentToken.text

                    if (nextToken == null || nextToken.type == TokenType.OneLineComment) {
                        isCodeAlignCompleted[i] = true
                    } else {
                        resultLines[i] += " "
                    }
                } else if (currentToken.type in listOf(TokenType.Comma, TokenType.Colon)) {

                    if (nextToken == null || nextToken.type == TokenType.OneLineComment) {
                        resultLines[i] += currentToken.text
                        isCodeAlignCompleted[i] = true
                    } else {
                        resultLines[i] += " ".repeat(paddingNum) + tokenRightPadding + currentToken.text + " "
                    }
                } else {
                    throw IllegalArgumentException("Not supported token type to align. " + currentToken.type)
                }

                alignedTokenIndexes[i] = alignedTokenIndexes[i] + 1
                didProcess = true
            }
        } while (didProcess)

        //
        // align one line comment.
        val furthestLength = resultLines.findFurthestLength()
        lineRange.lines.forEachIndexed { i, line ->
            val currentTokenIndex = alignedTokenIndexes[i] + 1
            if (currentTokenIndex >= line.tokens.size) return@forEachIndexed

            val paddingNum = furthestLength - resultLines[i].length
            val comment = line.tokens[currentTokenIndex].text
            if (currentTokenIndex != 0) {
                resultLines[i] += " "
            }
            resultLines[i] += " ".repeat(paddingNum) + comment
        }

        return resultLines
    }

    /**
     * Detect lines to align around the specified [anchor] line.
     */
    private fun detectLinesToAlign(rawLines: List<String>, anchor: Int, alignTargetTokens: List<TokenType>, tokenLexers: List<TokenLexer>): LineRange {
        // option
        val distinctBracketPattern = false

        val lexer = Lexer(tokenLexers)
        val anchorLine = Line(lexer.tokenize(rawLines[anchor]))
        val lineRange = LineRange(anchor, mutableListOf(anchorLine))
        var commonTokens = anchorLine.intersect(alignTargetTokens)

        // find start line to align.
        for (i in anchor - 1 downTo 0) {
            val line = Line(lexer.tokenize(rawLines[i]))

            val ct = commonTokens.intersect(line.intersect(alignTargetTokens))
            if (ct.isEmpty()) {
                break
            }
            if (distinctBracketPattern) {
                if (!line.isSamePattern(anchorLine, TokenType.Bracket)) {
                    break
                }
            }

            commonTokens = ct
            lineRange.addHead(line)
        }

        // find end line to align.
        for (i in anchor + 1 until rawLines.size) {
            val line = Line(lexer.tokenize(rawLines[i]))

            val ct = commonTokens.intersect(line.intersect(alignTargetTokens))
            if (ct.isEmpty()) {
                break
            }
            if (distinctBracketPattern) {
                if (!line.isSamePattern(anchorLine, TokenType.Bracket)) {
                    break
                }
            }

            commonTokens = ct
            lineRange.addTail(line)
        }

        return lineRange
    }

    private fun findLineSeparator(text: String): String {
        val lineSeparatorIndex = text.indexOf("\n")

        if (lineSeparatorIndex == -1) return ""
        if (lineSeparatorIndex == 0) return "\n"
        return if (text[lineSeparatorIndex - 1] == '\r') "\r\n" else "\n"
    }
}