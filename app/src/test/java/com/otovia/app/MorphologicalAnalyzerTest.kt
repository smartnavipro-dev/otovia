package com.otovia.app

import com.otovia.app.ocr.MorphologicalAnalyzer
import org.junit.Test

class MorphologicalAnalyzerTest {

    @Test
    fun testPhase2Corrections() {
        val analyzer = MorphologicalAnalyzer()

        // Test text with OCR errors that Phase 2 should correct
        val testCases = listOf(
            "需要の則は、雑済学に おける万有引力の洪則のようなものだ。" to
                    listOf("雑済学" to "経済学", "洪則" to "法則"),

            "価済が上がるとその財の需要量は減少する。" to
                    listOf("価済" to "価格"),

            "このような洪則は、雑済学の基本的な原理として広く認識されている。" to
                    listOf("洪則" to "法則", "雑済学" to "経済学")
        )

        println("========== Phase 2 Correction Test ==========")

        testCases.forEachIndexed { index, (input, expectedCorrections) ->
            println("\n[Test Case ${index + 1}]")
            println("Input: $input")
            println("Expected corrections: $expectedCorrections")

            val (corrected, corrections) = analyzer.applyContextualCorrection(input)

            println("Output: $corrected")
            println("Actual corrections: $corrections")

            // Verify expected corrections were made
            expectedCorrections.forEach { (from, to) ->
                if (corrected.contains(to)) {
                    println("✓ SUCCESS: '$from' → '$to'")
                } else {
                    println("✗ FAILED: '$from' was not corrected to '$to'")
                    println("  Output still contains: ${if (corrected.contains(from)) "'$from'" else "neither '$from' nor '$to'"}")
                }
            }
        }

        println("\n===========================================")
    }
}
