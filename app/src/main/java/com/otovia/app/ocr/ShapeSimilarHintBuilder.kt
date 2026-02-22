package com.otovia.app.ocr

import android.util.Log

/**
 * v1.1.32b: 形状類似文字ヒントビルダー（Stage 2 — LLMヒント生成）
 *
 * OCRテキスト中の「形状類似データベースに含まれる文字」をスキャンし、
 * LLMプロンプトに追加するコンパクトなヒント文字列を生成する。
 *
 * LLMが文脈を考慮して正しい補正を判断するための情報提供。
 * 直接的な置換は行わない（それはCharTypeAnomalyCorrectorの役割）。
 *
 * 重要: KANJI_KANA と KATAKANA_KATAKANA のみヒント対象。
 * KANJI_KANJI ヒント（例: 指↔脂）はLLMを誤誘導するリスクが高い。
 * LLMは漢字の文脈判断を自力で行えるため、不要なヒントは有害。
 * 例: 「指置」→ヒント「指↔脂」→LLM誤出力「脂置」（正解は「措置」）
 *
 * 出力例: "力↔カ,工↔エ,ン↔ソ"
 */
class ShapeSimilarHintBuilder {

    companion object {
        private const val TAG = "Otovia_HintBuilder"
        private const val MAX_HINTS = 8  // プロンプトトークン節約のため上限

        // LLMヒントに含めるカテゴリ（LLMが自力で判断しにくいもののみ）
        // KANJI_KANJI は除外: LLMの漢字文脈理解を誤誘導するリスクが高い
        // HIRAGANA_HIRAGANA は除外: ひらがなOCR誤りは稀
        // NUMBER_SYMBOL は除外: 日本語テキストフローに無関係
        private val HINT_CATEGORIES = setOf(
            ShapeSimilarDB.Category.KANJI_KANA,        // 漢字↔カナ混同（LLMに最も有用）
            ShapeSimilarDB.Category.KATAKANA_KATAKANA   // カタカナ同士（ン↔ソ, シ↔ツ等）
        )
    }

    /**
     * テキストに含まれる疑わしい文字をスキャンし、ヒント文字列を生成する。
     *
     * @param text OCRテキスト（Phase 1補正後）
     * @return ヒント文字列（例: "力↔カ,工↔エ,日↔目"）、該当なしの場合null
     */
    fun buildHints(text: String): String? {
        if (text.isEmpty()) return null

        // テキスト中の文字で、形状類似DBに含まれるものを収集
        val foundChars = mutableSetOf<Char>()
        for (c in text) {
            if (c in ShapeSimilarDB.allSuspectChars) {
                foundChars.add(c)
            }
        }

        if (foundChars.isEmpty()) return null

        // 各文字について形状類似ペアを取得し、ヒントを構築
        data class HintEntry(
            val char1: Char,
            val char2: Char,
            val frequency: ShapeSimilarDB.Frequency,
            val category: ShapeSimilarDB.Category
        )

        val hints = mutableListOf<HintEntry>()
        val seenPairs = mutableSetOf<String>()  // 重複排除用

        for (c in foundChars) {
            val pairs = ShapeSimilarDB.char1Index[c] ?: continue
            for (pair in pairs) {
                // LLMに有用なカテゴリのみ（KANJI_KANJIは除外）
                if (pair.category !in HINT_CATEGORIES) continue

                // 双方向ペアの重複を排除（A↔BとB↔Aは同じ）
                val pairKey = if (pair.char1 < pair.char2) "${pair.char1}${pair.char2}" else "${pair.char2}${pair.char1}"
                if (pairKey in seenPairs) continue
                seenPairs.add(pairKey)

                hints.add(HintEntry(pair.char1, pair.char2, pair.frequency, pair.category))
            }
        }

        if (hints.isEmpty()) return null

        // 優先順位でソート: HIGH > MEDIUM > LOW、漢字↔カナ > カタカナ同士
        val sorted = hints.sortedWith(
            compareBy(
                { when (it.frequency) {
                    ShapeSimilarDB.Frequency.HIGH -> 0
                    ShapeSimilarDB.Frequency.MEDIUM -> 1
                    ShapeSimilarDB.Frequency.LOW -> 2
                }},
                { when (it.category) {
                    ShapeSimilarDB.Category.KANJI_KANA -> 0
                    ShapeSimilarDB.Category.KATAKANA_KATAKANA -> 1
                    else -> 2  // フィルタ済みのため到達しない
                }}
            )
        )

        val top = sorted.take(MAX_HINTS)
        val hintStr = top.joinToString(",") { "${it.char1}↔${it.char2}" }

        Log.d(TAG, "[v1.1.32b] Found ${foundChars.size} suspect chars → ${top.size} hints (KANJI_KANA+KATAKANA only): $hintStr")
        return hintStr
    }
}
