package com.otovia.app.ocr

import android.util.Log

/**
 * OCR補正の妥当性検証クラス (v1.0.39)
 *
 * 目的:
 * - 誤補正（false positive）の防止
 * - 正しいテキストが誤って変更されることを防ぐ
 * - 補正の信頼度を評価
 */
class CorrectionValidator {

    companion object {
        private const val TAG = "Otovia_Validator"

        /**
         * 保護すべき正しい用語のホワイトリスト
         *
         * これらの用語は絶対に補正しない
         * v1.0.39: 経済学の基本用語を登録
         */
        private val protectedTerms = setOf(
            // 基本経済用語
            "需要", "供給", "価格", "市場", "経済", "経済学",
            "消費", "生産", "投資", "貿易", "金利", "物価",

            // マクロ経済学
            "景気", "不況", "好況", "インフレ", "デフレ",
            "財政", "税金", "所得", "国債", "赤字", "黒字",
            "通貨", "貨幣", "為替", "輸出", "輸入", "関税",

            // ミクロ経済学
            "効果", "弾力性", "競争", "独占", "寡占",
            "限界", "費用", "収入", "利潤", "損失",

            // その他
            "法則", "理論", "政府", "企業", "家計",
            "土地", "資本", "労働", "技術", "情報"
        )

        /**
         * 文脈依存の保護パターン
         *
         * 特定の文脈では補正してはいけないパターン
         */
        private val protectedContextPatterns = listOf(
            // 「～の法則」パターン
            Regex("(需要|供給|セイ|ワグナー|グレシャム)の法則"),

            // 「～効果」パターン
            Regex("(所得|代替|資産|流動性|マンデル・フレミング)効果"),

            // 「～理論」パターン
            Regex("(比較優位|一般均衡|限界革命|ケインズ|貨幣数量)理論"),

            // 学者名
            Regex("[A-Z][a-z]+の(理論|法則|仮説|モデル)")
        )

        /**
         * 補正してはいけない文字パターン
         *
         * 誤補正を引き起こしやすいパターン
         */
        private val forbiddenSubstitutions = mapOf(
            // "問題"を"間題"に補正してはいけない
            "問題" to listOf("間題", "問堤"),

            // "方法"を"万法"に補正してはいけない
            "方法" to listOf("万法", "方洪"),

            // "経験"を"経検"に補正してはいけない
            "経験" to listOf("経検", "経験")
        )
    }

    /**
     * 補正の妥当性を検証
     *
     * @param original 元のテキスト
     * @param corrected 補正後のテキスト
     * @return 検証結果（valid, reason）
     */
    fun validate(original: String, corrected: String): ValidationResult {
        // 変更がない場合は常に有効
        if (original == corrected) {
            return ValidationResult(
                valid = true,
                confidence = 1.0,
                reason = "No changes"
            )
        }

        // ホワイトリストチェック
        val protectedViolation = checkProtectedTerms(original, corrected)
        if (protectedViolation != null) {
            return protectedViolation
        }

        // 文脈パターンチェック
        val contextViolation = checkProtectedContexts(original, corrected)
        if (contextViolation != null) {
            return contextViolation
        }

        // 禁止置換チェック
        val forbiddenViolation = checkForbiddenSubstitutions(original, corrected)
        if (forbiddenViolation != null) {
            return forbiddenViolation
        }

        // 変更量チェック
        val changeRatio = calculateChangeRatio(original, corrected)
        if (changeRatio > 0.5) {
            return ValidationResult(
                valid = false,
                confidence = 0.0,
                reason = "Too many changes (${(changeRatio * 100).toInt()}%)"
            )
        }

        // 信頼度計算
        val confidence = calculateConfidence(original, corrected, changeRatio)

        return ValidationResult(
            valid = true,
            confidence = confidence,
            reason = "Valid correction"
        )
    }

    /**
     * ホワイトリスト用語の保護チェック
     */
    private fun checkProtectedTerms(
        original: String,
        corrected: String
    ): ValidationResult? {
        // 元のテキストに保護用語が含まれている場合
        val originalTerms = protectedTerms.filter { original.contains(it) }
        val correctedTerms = protectedTerms.filter { corrected.contains(it) }

        // 保護用語が消えた場合
        val removed = originalTerms - correctedTerms.toSet()
        if (removed.isNotEmpty()) {
            Log.w(TAG, "[Validator] Protected term removed: ${removed.joinToString()}")
            return ValidationResult(
                valid = false,
                confidence = 0.0,
                reason = "Protected term removed: ${removed.first()}"
            )
        }

        return null  // 問題なし
    }

    /**
     * 文脈依存の保護パターンチェック
     */
    private fun checkProtectedContexts(
        original: String,
        corrected: String
    ): ValidationResult? {
        protectedContextPatterns.forEach { pattern ->
            val originalMatch = pattern.find(original)
            val correctedMatch = pattern.find(corrected)

            if (originalMatch != null && correctedMatch == null) {
                Log.w(TAG, "[Validator] Protected context broken: ${originalMatch.value}")
                return ValidationResult(
                    valid = false,
                    confidence = 0.0,
                    reason = "Protected context broken: ${originalMatch.value}"
                )
            }
        }

        return null  // 問題なし
    }

    /**
     * 禁止置換チェック
     */
    private fun checkForbiddenSubstitutions(
        original: String,
        corrected: String
    ): ValidationResult? {
        forbiddenSubstitutions.forEach { (correct, forbidden) ->
            // 正しい用語が禁止された形に変換されていないかチェック
            if (original.contains(correct)) {
                forbidden.forEach { forbiddenForm ->
                    if (corrected.contains(forbiddenForm) && !original.contains(forbiddenForm)) {
                        Log.w(TAG, "[Validator] Forbidden substitution: $correct → $forbiddenForm")
                        return ValidationResult(
                            valid = false,
                            confidence = 0.0,
                            reason = "Forbidden substitution: $correct → $forbiddenForm"
                        )
                    }
                }
            }
        }

        return null  // 問題なし
    }

    /**
     * 変更率の計算
     */
    private fun calculateChangeRatio(original: String, corrected: String): Double {
        val distance = levenshteinDistance(original, corrected)
        val maxLength = maxOf(original.length, corrected.length)
        return distance.toDouble() / maxLength
    }

    /**
     * 信頼度の計算
     *
     * 基準:
     * - 変更が少ない: 信頼度高
     * - 保護用語が増えた: 信頼度高
     * - 変更が多い: 信頼度低
     */
    private fun calculateConfidence(
        original: String,
        corrected: String,
        changeRatio: Double
    ): Double {
        var confidence = 1.0 - changeRatio

        // 保護用語が増えた場合は信頼度を上げる
        val originalTermCount = protectedTerms.count { original.contains(it) }
        val correctedTermCount = protectedTerms.count { corrected.contains(it) }

        if (correctedTermCount > originalTermCount) {
            confidence += 0.2 * (correctedTermCount - originalTermCount)
        }

        return confidence.coerceIn(0.0, 1.0)
    }

    /**
     * レーベンシュタイン距離の計算
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }

        return dp[len1][len2]
    }

    /**
     * 検証結果のデータクラス
     */
    data class ValidationResult(
        val valid: Boolean,
        val confidence: Double,
        val reason: String
    )
}
