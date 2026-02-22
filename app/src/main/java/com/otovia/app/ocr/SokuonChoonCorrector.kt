package com.otovia.app.ocr

import android.util.Log
import com.atilika.kuromoji.unidic.Tokenizer
import kotlin.math.min

/**
 * v1.0.69: 促音・長音誤認識補正（Phase 1 + Phase 2 + Phase 3）
 *
 * Phase 1: |、1、l、I → ー の高信頼度補正（v1.0.66）
 * Phase 2: 長音脱落検出（コンピュタ→コンピューター）（v1.0.69）
 * Phase 3: 促音補正（がつこう→がっこう、がこう→がっこう）（v1.0.69）
 */
class SokuonChoonCorrector {

    companion object {
        private const val TAG = "Otovia_SokuonChoon"

        // v1.0.71: UniDic形態素解析器（Phase3SharedTokenizerを使用）
        private val unidicTokenizer get() = Phase3SharedTokenizer.unidicTokenizer

        /**
         * Phase 1: 長音誤認識パターン（高信頼度）
         */
        private val choonMisrecognitionChars = mapOf(
            '|' to "縦線（パイプ）",
            '｜' to "全角縦線",
            '1' to "数字の1（半角）",
            '１' to "数字の1（全角）",
            'l' to "小文字のL",
            'I' to "大文字のI（アイ）"
        )

        /**
         * Phase 1: 頻出カタカナ語辞書（長音を含む）- 簡易版
         */
        private val frequentKatakanaWords = setOf(
            // IT用語
            "コンピューター", "サーバー", "ユーザー", "データー",
            "ネットワーク", "インターネット", "プログラム", "システム",
            "ファイル", "フォルダー", "プリンター", "スキャナー",
            "ルーター", "モニター", "キーボード", "マウス",

            // 飲食
            "コーヒー", "ティー", "ジュース", "ビール", "ワイン",
            "ケーキ", "クッキー", "チーズ", "バター", "ヨーグルト",

            // 日常
            "カレンダー", "スケジュール", "メール", "メッセージ",
            "レター", "ペーパー", "ノート", "ペン", "マーカー",

            // 乗り物
            "カー", "バス", "タクシー", "トレイン", "フェリー",
            "ヘリコプター"
        )
        /**
         * Phase 2: カタカナ語データクラス（長音脱落検出用）
         */
        data class KatakanaWord(
            val word: String,              // コンピューター
            val withoutChoon: String,      // コンピュタ（長音削除版）
            val frequency: Int,            // 使用頻度 (1-10)
            val category: String           // カテゴリ
        )

        /**
         * Phase 3: 促音を含む単語データクラス
         */
        data class SokuonWord(
            val word: String,              // がっこう
            val withoutSokuon: String,     // がこう（促音削除版）
            val withTsu: String,           // がつこう（つ版）
            val sokuonPosition: Int,       // 1（促音の位置）
            val frequency: Int,            // 使用頻度 (1-10)
            val category: String           // カテゴリ
        )

        /**
         * Phase 2: 長音を含むカタカナ語辞書（100語）
         */
        private val katakanaWordDict = listOf(
            // IT用語（50語）
            KatakanaWord("コンピューター", "コンピュタ", 10, "IT"),
            KatakanaWord("サーバー", "サバ", 10, "IT"),
            KatakanaWord("ユーザー", "ユザ", 10, "IT"),
            KatakanaWord("データー", "デタ", 9, "IT"),
            KatakanaWord("ネットワーク", "ネットワク", 9, "IT"),
            KatakanaWord("インターネット", "インタネット", 10, "IT"),
            KatakanaWord("プログラム", "プログラム", 8, "IT"), // 長音なし
            KatakanaWord("システム", "システム", 9, "IT"), // 長音なし
            KatakanaWord("ソフトウェア", "ソフトウエア", 8, "IT"),
            KatakanaWord("ハードウェア", "ハドウエア", 7, "IT"),
            KatakanaWord("ファイル", "ファイル", 9, "IT"), // 長音なし
            KatakanaWord("フォルダー", "フォルダ", 8, "IT"),
            KatakanaWord("プリンター", "プリンタ", 7, "IT"),
            KatakanaWord("スキャナー", "スキャナ", 6, "IT"),
            KatakanaWord("ルーター", "ルタ", 7, "IT"),
            KatakanaWord("モニター", "モニタ", 8, "IT"),
            KatakanaWord("キーボード", "キボド", 8, "IT"),
            KatakanaWord("マウス", "マウス", 8, "IT"), // 長音なし
            KatakanaWord("メモリー", "メモリ", 7, "IT"),
            KatakanaWord("プロセッサー", "プロセッサ", 6, "IT"),
            KatakanaWord("ブラウザー", "ブラウザ", 8, "IT"),
            KatakanaWord("エディター", "エディタ", 7, "IT"),
            KatakanaWord("コンパイラー", "コンパイラ", 5, "IT"),
            KatakanaWord("デバッガー", "デバッガ", 5, "IT"),
            KatakanaWord("アプリケーション", "アプリケション", 8, "IT"),
            KatakanaWord("セキュリティー", "セキュリティ", 8, "IT"),
            KatakanaWord("データベース", "デタベス", 8, "IT"),
            KatakanaWord("インターフェース", "インタフェス", 7, "IT"),
            KatakanaWord("アーキテクチャー", "アキテクチャ", 6, "IT"),
            KatakanaWord("パラメーター", "パラメタ", 7, "IT"),
            KatakanaWord("ポインター", "ポインタ", 6, "IT"),
            KatakanaWord("レジスター", "レジスタ", 5, "IT"),
            KatakanaWord("コントローラー", "コントロラ", 6, "IT"),
            KatakanaWord("マネージャー", "マネジャ", 7, "IT"),
            KatakanaWord("オペレーター", "オペレタ", 6, "IT"),
            KatakanaWord("スケジューラー", "スケジュラ", 5, "IT"),
            KatakanaWord("バッファー", "バッファ", 6, "IT"),
            KatakanaWord("キャッシュメモリー", "キャッシュメモリ", 5, "IT"),
            KatakanaWord("センサー", "センサ", 6, "IT"),
            KatakanaWord("スピーカー", "スピカ", 7, "IT"),
            KatakanaWord("レシーバー", "レシバ", 6, "IT"),
            KatakanaWord("トランスミッター", "トランスミッタ", 5, "IT"),
            KatakanaWord("アダプター", "アダプタ", 6, "IT"),
            KatakanaWord("コネクター", "コネクタ", 6, "IT"),
            KatakanaWord("ケーブル", "ケブル", 7, "IT"),
            KatakanaWord("レーザー", "レザ", 6, "IT"),
            KatakanaWord("スクリーン", "スクリン", 7, "IT"),
            KatakanaWord("ディスプレー", "ディスプレ", 8, "IT"),
            KatakanaWord("タッチパネル", "タッチパネル", 7, "IT"), // 長音なし
            KatakanaWord("インストーラー", "インストラ", 6, "IT"),

            // 飲食（10語）
            KatakanaWord("コーヒー", "コヒ", 10, "飲食"),
            KatakanaWord("ティー", "ティ", 8, "飲食"),
            KatakanaWord("ジュース", "ジュス", 8, "飲食"),
            KatakanaWord("ビール", "ビル", 9, "飲食"),
            KatakanaWord("ワイン", "ワイン", 8, "飲食"), // 長音なし
            KatakanaWord("ケーキ", "ケキ", 8, "飲食"),
            KatakanaWord("クッキー", "クッキ", 7, "飲食"),
            KatakanaWord("チーズ", "チズ", 8, "飲食"),
            KatakanaWord("バター", "バタ", 8, "飲食"),
            KatakanaWord("ヨーグルト", "ヨグルト", 7, "飲食"),

            // 日常（15語）
            KatakanaWord("カレンダー", "カレンダ", 7, "日常"),
            KatakanaWord("スケジュール", "スケジュル", 8, "日常"),
            KatakanaWord("メール", "メル", 9, "日常"),
            KatakanaWord("メッセージ", "メッセジ", 8, "日常"),
            KatakanaWord("レター", "レタ", 6, "日常"),
            KatakanaWord("ペーパー", "ペパ", 7, "日常"),
            KatakanaWord("ノート", "ノト", 8, "日常"),
            KatakanaWord("ペン", "ペン", 7, "日常"), // 長音なし
            KatakanaWord("マーカー", "マカ", 6, "日常"),
            KatakanaWord("テーブル", "テブル", 7, "日常"),
            KatakanaWord("チェア", "チェア", 6, "日常"), // 長音なし
            KatakanaWord("ドア", "ドア", 7, "日常"), // 長音なし
            KatakanaWord("ウィンドー", "ウィンド", 6, "日常"),
            KatakanaWord("フロアー", "フロア", 6, "日常"),
            KatakanaWord("シャワー", "シャワ", 7, "日常"),

            // 乗り物（10語）
            KatakanaWord("カー", "カ", 7, "乗り物"),
            KatakanaWord("バス", "バス", 8, "乗り物"), // 長音なし
            KatakanaWord("タクシー", "タクシ", 8, "乗り物"),
            KatakanaWord("トレイン", "トレイン", 7, "乗り物"), // 長音なし
            KatakanaWord("フェリー", "フェリ", 6, "乗り物"),
            KatakanaWord("ヘリコプター", "ヘリコプタ", 6, "乗り物"),
            KatakanaWord("モーター", "モタ", 6, "乗り物"),
            KatakanaWord("エンジン", "エンジン", 7, "乗り物"), // 長音なし
            KatakanaWord("タイヤ", "タイヤ", 7, "乗り物"), // 長音なし
            KatakanaWord("ハンドル", "ハンドル", 7, "乗り物"), // 長音なし

            // 医療（10語）
            KatakanaWord("ドクター", "ドクタ", 8, "医療"),
            KatakanaWord("ナース", "ナス", 7, "医療"),
            KatakanaWord("ケア", "ケア", 7, "医療"), // 長音なし
            KatakanaWord("センター", "センタ", 8, "医療"),
            KatakanaWord("クリニック", "クリニック", 7, "医療"), // 長音なし
            KatakanaWord("カテーテル", "カテテル", 6, "医療"),
            KatakanaWord("レントゲン", "レントゲン", 6, "医療"), // 長音なし
            KatakanaWord("エコー", "エコ", 6, "医療"),
            KatakanaWord("マーカー", "マカ", 5, "医療"),
            KatakanaWord("モニター", "モニタ", 6, "医療"),

            // 金融（5語）
            KatakanaWord("ユーロ", "ユロ", 7, "金融"),
            KatakanaWord("ドル", "ドル", 8, "金融"), // 長音なし
            KatakanaWord("マネー", "マネ", 7, "金融"),
            KatakanaWord("セクター", "セクタ", 6, "金融"),
            KatakanaWord("トレーダー", "トレダ", 6, "金融")
        )

        /**
         * Phase 3: 促音を含む単語辞書（50語）
         */
        private val sokuonWordDict = listOf(
            // 日常基本語（20語）
            SokuonWord("がっこう", "がこう", "がつこう", 1, 10, "日常"),
            SokuonWord("きって", "きて", "きつて", 1, 8, "日常"),
            SokuonWord("ずっと", "ずと", "ずつと", 1, 9, "日常"),
            SokuonWord("しっかり", "しかり", "しつかり", 1, 8, "日常"),
            SokuonWord("いっしょ", "いしょ", "いつしょ", 1, 9, "日常"),
            SokuonWord("ちょっと", "ちょと", "ちょつと", 2, 9, "日常"),
            SokuonWord("いっぱい", "いぱい", "いつぱい", 1, 8, "日常"),
            SokuonWord("ばっかり", "ばかり", "ばつかり", 1, 7, "日常"),
            SokuonWord("ぜったい", "ぜたい", "ぜつたい", 1, 8, "日常"),
            SokuonWord("しっぽ", "しぽ", "しつぽ", 1, 7, "日常"),
            SokuonWord("やっぱり", "やぱり", "やつぱり", 2, 8, "日常"),
            SokuonWord("さっき", "さき", "さつき", 1, 8, "日常"),
            SokuonWord("きっと", "きと", "きつと", 1, 8, "日常"),
            SokuonWord("もっと", "もと", "もつと", 1, 9, "日常"),
            SokuonWord("せっけん", "せけん", "せつけん", 1, 7, "日常"),
            SokuonWord("こっち", "こち", "こつち", 1, 8, "日常"),
            SokuonWord("あっち", "あち", "あつち", 1, 8, "日常"),
            SokuonWord("そっち", "そち", "そつち", 1, 8, "日常"),
            SokuonWord("どっち", "どち", "どつち", 1, 8, "日常"),
            SokuonWord("まっすぐ", "ますぐ", "まつすぐ", 1, 8, "日常"),

            // 動詞過去形（16語）
            SokuonWord("やった", "やた", "やつた", 2, 8, "動詞"),
            SokuonWord("かった", "かた", "かつた", 2, 8, "動詞"),
            SokuonWord("のった", "のた", "のつた", 2, 7, "動詞"),
            SokuonWord("たった", "たた", "たつた", 2, 7, "動詞"),
            SokuonWord("もった", "もた", "もつた", 2, 8, "動詞"),
            SokuonWord("はった", "はた", "はつた", 2, 7, "動詞"),
            SokuonWord("まった", "また", "まつた", 2, 7, "動詞"),
            SokuonWord("こった", "こた", "こつた", 2, 6, "動詞"),
            SokuonWord("とった", "とた", "とつた", 2, 8, "動詞"),
            SokuonWord("うった", "うた", "うつた", 2, 7, "動詞"),
            SokuonWord("あった", "あた", "あつた", 2, 9, "動詞"),
            SokuonWord("いった", "いた", "いつた", 2, 9, "動詞"),
            SokuonWord("おった", "おた", "おつた", 2, 6, "動詞"),
            SokuonWord("よった", "よた", "よつた", 2, 7, "動詞"),
            SokuonWord("わった", "わた", "わつた", 2, 7, "動詞"),
            SokuonWord("なった", "なた", "なつた", 2, 9, "動詞"),

            // 形容詞（10語）
            SokuonWord("すっきり", "すきり", "すつきり", 1, 7, "形容詞"),
            SokuonWord("はっきり", "はきり", "はつきり", 1, 8, "形容詞"),
            SokuonWord("すっぱい", "すぱい", "すつぱい", 1, 6, "形容詞"),
            SokuonWord("あっさり", "あさり", "あつさり", 1, 7, "形容詞"),
            SokuonWord("さっぱり", "さぱり", "さつぱり", 1, 7, "形容詞"),
            SokuonWord("ぴったり", "ぴたり", "ぴつたり", 2, 7, "形容詞"),
            SokuonWord("びっくり", "びくり", "びつくり", 1, 8, "形容詞"),
            SokuonWord("ほっそり", "ほそり", "ほつそり", 1, 6, "形容詞"),
            SokuonWord("がっしり", "がしり", "がつしり", 1, 6, "形容詞"),
            SokuonWord("しっとり", "しとり", "しつとり", 1, 6, "形容詞")
        )


        /**
         * Phase 2: 辞書インデックス（最初の2文字でハッシュ化）
         */
        private val dictIndex: Map<String, List<KatakanaWord>> by lazy {
            katakanaWordDict.groupBy { word ->
                if (word.withoutChoon.length >= 2) {
                    word.withoutChoon.take(2)
                } else {
                    word.withoutChoon
                }
            }
        }

        /**
         * Phase 3: 促音辞書インデックス（最初の2文字でハッシュ化）
         */
        private val sokuonDictIndex: Map<String, List<SokuonWord>> by lazy {
            val withoutSokuonIndex = sokuonWordDict.groupBy { word ->
                if (word.withoutSokuon.length >= 2) {
                    word.withoutSokuon.take(2)
                } else {
                    word.withoutSokuon
                }
            }

            val withTsuIndex = sokuonWordDict.groupBy { word ->
                if (word.withTsu.length >= 2) {
                    word.withTsu.take(2)
                } else {
                    word.withTsu
                }
            }

            // 両方のインデックスを統合
            (withoutSokuonIndex.keys + withTsuIndex.keys).associateWith { key ->
                (withoutSokuonIndex[key] ?: emptyList()) + (withTsuIndex[key] ?: emptyList())
            }.mapValues { it.value.distinct() }
        }

        /**
         * Phase 3: 子音マップ（音韻的文脈分析用）
         */
        private val consonantMap = mapOf(
            // か行（k音）
            'か' to 'k', 'き' to 'k', 'く' to 'k', 'け' to 'k', 'こ' to 'k',
            'が' to 'g', 'ぎ' to 'g', 'ぐ' to 'g', 'げ' to 'g', 'ご' to 'g',

            // さ行（s音）
            'さ' to 's', 'し' to 's', 'す' to 's', 'せ' to 's', 'そ' to 's',
            'ざ' to 'z', 'じ' to 'z', 'ず' to 'z', 'ぜ' to 'z', 'ぞ' to 'z',

            // た行（t音）
            'た' to 't', 'ち' to 't', 'つ' to 't', 'て' to 't', 'と' to 't',
            'だ' to 'd', 'ぢ' to 'd', 'づ' to 'd', 'で' to 'd', 'ど' to 'd',

            // ぱ行（p音）
            'ぱ' to 'p', 'ぴ' to 'p', 'ぷ' to 'p', 'ぺ' to 'p', 'ぽ' to 'p',
            'ば' to 'b', 'び' to 'b', 'ぶ' to 'b', 'べ' to 'b', 'ぼ' to 'b'
        )

        /**
         * Phase 3: 促音が出現しやすい子音か判定
         */
        private fun isSokuonFriendlyConsonant(char: Char): Boolean {
            val consonant = consonantMap[char]
            return consonant in setOf('k', 'g', 's', 'z', 't', 'd', 'p', 'b')
        }

    }

    /**
     * Phase 1 + Phase 2統合検出
     */
    fun detectAndCorrect(text: String): ChoonResult {
        val tokens = unidicTokenizer.tokenize(text)
        val suggestions = mutableListOf<ChoonSuggestion>()

        Log.d(TAG, "[v1.0.69] Analyzing ${tokens.size} tokens for choon errors (Phase 1+2)")

        tokens.forEachIndexed { index, token ->
            // カタカナを含むトークンのみ処理
            if (containsKatakana(token.surface)) {
                // Phase 1: 高信頼度長音誤認識（|/1/l/I→ー）
                val hasMisrecognition = choonMisrecognitionChars.keys.any { char ->
                    token.surface.contains(char)
                }

                if (hasMisrecognition) {
                    val correctedForm = fixChoonMisrecognition(token.surface)

                    if (correctedForm != token.surface) {
                        val position = calculatePosition(tokens, index, text)
                        val confidence = calculatePhase1Confidence(token.surface, correctedForm)

                        suggestions.add(
                            ChoonSuggestion(
                                position = position,
                                originalForm = token.surface,
                                correctedForm = correctedForm,
                                confidence = confidence,
                                reason = "Phase1: 長音誤認識（|/1/l/I→ー）",
                                misrecognizedChars = getMisrecognizedChars(token.surface),
                                phase = 1
                            )
                        )

                        Log.d(TAG, "[v1.0.69 Phase1] '${token.surface}' → '$correctedForm' (conf=${String.format("%.2f", confidence)})")
                    }
                }

                // Phase 2: 長音脱落検出（辞書ベース）
                val omissionSuggestions = detectChoonOmission(token.surface, index, tokens, text)
                suggestions.addAll(omissionSuggestions)
            }
       

            // ひらがなを含むトークンを処理（Phase 3）
            if (containsHiragana(token.surface)) {
                // Phase 3a: つ→っ誤認識検出
                val tsuSuggestions = detectSokuonMisrecognition(token.surface, index, tokens, text)
                suggestions.addAll(tsuSuggestions)

                // Phase 3b: 促音脱落検出
                val sokuonOmissionSuggestions = detectSokuonOmission(token.surface, index, tokens, text)
                suggestions.addAll(sokuonOmissionSuggestions)
            }

         }

        Log.d(TAG, "[v1.0.69] Found ${suggestions.count { it.phase == 1 }} Phase1 + ${suggestions.count { it.phase == 2 }} Phase2 + ${suggestions.count { it.phase == 3 }} Phase3 patterns")

        return ChoonResult(
            originalText = text,
            suggestions = suggestions,
            confidence = calculateOverallConfidence(suggestions)
        )
    }

    /**
     * Phase 2: 長音脱落を検出
     */
    private fun detectChoonOmission(
        surface: String,
        index: Int,
        tokens: List<com.atilika.kuromoji.unidic.Token>,
        originalText: String
    ): List<ChoonSuggestion> {
        val suggestions = mutableListOf<ChoonSuggestion>()

        // 最初の2文字でインデックス検索
        val prefix = if (surface.length >= 2) surface.take(2) else surface
        val candidates = dictIndex[prefix] ?: emptyList()

        candidates.forEach { dictWord ->
            // 編集距離を計算
            val distance = levenshteinDistance(surface, dictWord.withoutChoon)

            // v1.1.28: 編集距離閾値を2→1に厳格化（インブプット→インターネット等の誤マッチ防止）
            // 長音脱落は通常1文字単位なので距離1で十分
            if (distance <= 1 && distance > 0) {
                val confidence = calculatePhase2Confidence(
                    surface,
                    dictWord.word,
                    distance,
                    dictWord.frequency
                )

                if (confidence >= 0.5) {
                    val position = calculatePosition(tokens, index, originalText)

                    suggestions.add(
                        ChoonSuggestion(
                            position = position,
                            originalForm = surface,
                            correctedForm = dictWord.word,
                            confidence = confidence,
                            reason = "Phase2: 長音脱落（編集距離=${distance}）",
                            misrecognizedChars = emptyList(),
                            phase = 2
                        )
                    )

                    Log.d(TAG, "[v1.0.69 Phase2] '$surface' → '${dictWord.word}' (conf=${String.format("%.2f", confidence)}, dist=$distance)")
                }
            }
        }

        return suggestions
    }

    /**
     * Levenshtein距離（編集距離）を計算
     */
    /**
     * Phase 3a: つ→っ誤認識を検出
     */
    private fun detectSokuonMisrecognition(
        surface: String,
        index: Int,
        tokens: List<com.atilika.kuromoji.unidic.Token>,
        originalText: String
    ): List<ChoonSuggestion> {
        val suggestions = mutableListOf<ChoonSuggestion>()
        if (!surface.contains("つ")) return suggestions
        val prefix = if (surface.length >= 2) surface.take(2) else surface
        val candidates = sokuonDictIndex[prefix] ?: emptyList()
        candidates.forEach { dictWord ->
            if (surface == dictWord.withTsu) {
                val charAfterTsu = if (dictWord.sokuonPosition < dictWord.word.length - 1) {
                    dictWord.word[dictWord.sokuonPosition + 1]
                } else { null }
                val isPhoneticallyValid = charAfterTsu?.let { isSokuonFriendlyConsonant(it) } ?: false
                val confidence = calculateSokuonConfidence(surface, dictWord.word, dictWord.frequency, isPhoneticallyValid, matchType = "tsu")
                if (confidence >= 0.60) {
                    val position = calculatePosition(tokens, index, originalText)
                    suggestions.add(ChoonSuggestion(position = position, originalForm = surface, correctedForm = dictWord.word, confidence = confidence, reason = "Phase3a: つ→っ誤認識", misrecognizedChars = listOf('つ'), phase = 3))
                    Log.d(TAG, "[v1.0.69 Phase3a] '$surface' → '${dictWord.word}' (conf=${String.format("%.2f", confidence)})")
                }
            }
        }
        return suggestions
    }

    /**
     * Phase 3b: 促音脱落を検出
     */
    private fun detectSokuonOmission(
        surface: String,
        index: Int,
        tokens: List<com.atilika.kuromoji.unidic.Token>,
        originalText: String
    ): List<ChoonSuggestion> {
        val suggestions = mutableListOf<ChoonSuggestion>()
        val prefix = if (surface.length >= 2) surface.take(2) else surface
        val candidates = sokuonDictIndex[prefix] ?: emptyList()
        candidates.forEach { dictWord ->
            val distance = levenshteinDistance(surface, dictWord.withoutSokuon)
            if (distance == 1) {
                val charAfterSokuon = if (dictWord.sokuonPosition < dictWord.word.length - 1) {
                    dictWord.word[dictWord.sokuonPosition + 1]
                } else { null }
                val isPhoneticallyValid = charAfterSokuon?.let { isSokuonFriendlyConsonant(it) } ?: false
                val confidence = calculateSokuonConfidence(surface, dictWord.word, dictWord.frequency, isPhoneticallyValid, matchType = "omission")
                if (confidence >= 0.60) {
                    val position = calculatePosition(tokens, index, originalText)
                    suggestions.add(ChoonSuggestion(position = position, originalForm = surface, correctedForm = dictWord.word, confidence = confidence, reason = "Phase3b: 促音脱落（編集距離=${distance}）", misrecognizedChars = emptyList(), phase = 3))
                    Log.d(TAG, "[v1.0.69 Phase3b] '$surface' → '${dictWord.word}' (conf=${String.format("%.2f", confidence)}, dist=$distance)")
                }
            }
        }
        return suggestions
    }

    /**
     * Phase 3: 促音補正の信頼度を計算
     */
    private fun calculateSokuonConfidence(
        original: String,
        corrected: String,
        frequency: Int,
        isPhoneticallyValid: Boolean,
        matchType: String
    ): Double {
        var score = 0.0
        score += when (matchType) { "tsu" -> 0.30; "omission" -> 0.25; else -> 0.10 }
        val frequencyScore = frequency / 10.0 * 0.3
        score += frequencyScore
        score += if (isPhoneticallyValid) 0.25 else 0.10
        val hiraganaPurity = corrected.count { it in 'ぁ'..'ん' }.toDouble() / corrected.length
        score += hiraganaPurity * 0.15
        return score.coerceIn(0.0, 1.0)
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }

        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // 削除
                    dp[i][j - 1] + 1,      // 挿入
                    dp[i - 1][j - 1] + cost  // 置換
                )
            }
        }

        return dp[s1.length][s2.length]
    }

    /**
     * カタカナが含まれているか判定
     */
    private fun containsKatakana(text: String): Boolean {
        return text.any { it in 'ァ'..'ヴ' || it == 'ー' }
    }

    /**
     * ひらがなが含まれているか判定
     */
    private fun containsHiragana(text: String): Boolean {
        return text.any { it in 'ぁ'..'ん' }
    }


    /**
     * Phase 1: 長音誤認識文字を「ー」に修正
     */
    private fun fixChoonMisrecognition(text: String): String {
        var result = text

        choonMisrecognitionChars.keys.forEach { char ->
            result = result.replace(char, 'ー')
        }

        return result
    }

    /**
     * Phase 1: 誤認識された文字のリストを取得
     */
    private fun getMisrecognizedChars(text: String): List<Char> {
        return text.filter { choonMisrecognitionChars.containsKey(it) }.toList()
    }

    /**
     * Phase 1: 信頼度を計算
     */
    private fun calculatePhase1Confidence(original: String, corrected: String): Double {
        var score = 0.0

        // 1. 基本信頼度: 長音誤認識パターンは高信頼度（50%）
        score += 0.5

        // 2. 頻出カタカナ語辞書マッチ（30%）
        val inDictionary = frequentKatakanaWords.contains(corrected)
        score += if (inDictionary) 0.3 else 0.1

        // 3. カタカナ純度（20%）
        val katakanaPurity = corrected.count { it in 'ァ'..'ヴ' || it == 'ー' }.toDouble() / corrected.length
        score += katakanaPurity * 0.2

        return score.coerceIn(0.0, 1.0)
    }

    /**
     * Phase 2: 信頼度を計算（辞書ベース）
     */
    private fun calculatePhase2Confidence(
        original: String,
        corrected: String,
        distance: Int,
        frequency: Int
    ): Double {
        var score = 0.0

        // 1. 編集距離ベーススコア（40%）
        val distanceScore = when (distance) {
            0 -> 0.0        // 変更なし
            1 -> 0.4        // ー1個脱落: 40%
            2 -> 0.35       // ー2個脱落: 35%
            3 -> 0.25       // ー3個脱落 or 複雑: 25%
            else -> 0.1     // 複雑な誤認識: 10%
        }
        score += distanceScore

        // 2. 単語頻度スコア（30%）
        val frequencyScore = frequency / 10.0 * 0.3  // frequency: 1-10
        score += frequencyScore

        // 3. 長音挿入純度（20%） - 簡易版: 編集距離が小さいほど純度が高い
        val purityScore = if (distance <= 2) 0.2 else 0.1
        score += purityScore

        // 4. カタカナ純度（10%）
        val katakanaPurity = corrected.count { it in 'ァ'..'ヴ' || it == 'ー' }.toDouble() / corrected.length
        score += katakanaPurity * 0.1

        return score.coerceIn(0.0, 1.0)
    }

    /**
     * テキスト内の位置を計算
     */
    private fun calculatePosition(tokens: List<com.atilika.kuromoji.unidic.Token>, index: Int, originalText: String): Int {
        var pos = 0
        for (i in 0 until index) {
            pos += tokens[i].surface.length
        }
        return pos
    }

    /**
     * 全体の信頼度を計算
     */
    private fun calculateOverallConfidence(suggestions: List<ChoonSuggestion>): Double {
        if (suggestions.isEmpty()) return 0.0
        return suggestions.map { it.confidence }.average()
    }

    /**
     * 長音補正を適用（Phase 1 + Phase 2 + Phase 3）
     */
    fun applyCorrections(result: ChoonResult, minConfidence: Double = 0.60): String {
        if (result.suggestions.isEmpty()) {
            return result.originalText
        }

        var correctedText = result.originalText
        var offset = 0  // 補正によるオフセット

        // Phase 1（高信頼度）を先に適用、次にPhase 2、最後にPhase 3を適用
        val phase1Suggestions = result.suggestions.filter { it.phase == 1 && it.confidence >= 0.65 }
        val phase2Suggestions = result.suggestions.filter { it.phase == 2 && it.confidence >= minConfidence }
        val phase3Suggestions = result.suggestions.filter { it.phase == 3 && it.confidence >= minConfidence }
        val allSuggestions = (phase1Suggestions + phase2Suggestions + phase3Suggestions).sortedBy { it.position }

        allSuggestions.forEach { suggestion ->
            val replacePos = suggestion.position + offset

            if (replacePos <= correctedText.length) {
                val endPos = replacePos + suggestion.originalForm.length

                if (endPos <= correctedText.length) {
                    // 文字列置換
                    correctedText = correctedText.substring(0, replacePos) +
                                   suggestion.correctedForm +
                                   correctedText.substring(endPos)

                    // オフセット更新
                    val lengthDiff = suggestion.correctedForm.length - suggestion.originalForm.length
                    offset += lengthDiff

                    Log.d(TAG, "[v1.0.69] Applied: '${suggestion.originalForm}' → '${suggestion.correctedForm}' (conf=${String.format("%.2f", suggestion.confidence)}, Phase${suggestion.phase})")
                }
            }
        }

        val phase1Count = phase1Suggestions.size
        val phase2Count = phase2Suggestions.size
        val phase3Count = phase3Suggestions.size
        Log.d(TAG, "[v1.0.69] Choon corrections applied: Phase1=$phase1Count, Phase2=$phase2Count, Phase3=$phase3Count, Total=${phase1Count + phase2Count + phase3Count}")
        return correctedText
    }

    /**
     * 長音補正の提案（Phase情報追加）
     */
    data class ChoonSuggestion(
        val position: Int,                   // 補正位置
        val originalForm: String,            // 元の形
        val correctedForm: String,           // 補正後の形
        val confidence: Double,              // 信頼度 (0.0-1.0)
        val reason: String,                  // 理由
        val misrecognizedChars: List<Char>,  // 誤認識された文字（Phase 1+3aのみ）
        val phase: Int                       // Phase番号（1, 2, or 3）
    )

    /**
     * 補正結果
     */
    data class ChoonResult(
        val originalText: String,
        val suggestions: List<ChoonSuggestion>,
        val confidence: Double
    )
}
