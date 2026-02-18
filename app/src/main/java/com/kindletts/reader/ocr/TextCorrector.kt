package com.kindletts.reader.ocr

import android.util.Log
import com.google.mlkit.vision.text.Text

/**
 * OCR認識後のテキスト補正クラス
 * v1.0.17: 経済用語・カタカナの誤認識を自動修正
 * v1.0.31: Phase 1 - 視覚的類似性パターン一般化
 * v1.0.32: Phase 2 - 形態素解析による未知語検出
 * v1.0.33: Phase 3 - 信頼度ベース候補選択
 * v1.0.39: Phase 1信頼度スコアリング + LLM統合
 */
class TextCorrector(private val context: android.content.Context) {

    companion object {
        private const val TAG = "KindleTTS_TextCorrector"

        /**
         * v1.0.37: Phase 2無効化フラグ
         * 理由: Kuromojiが誤認識テキストを既知単語として分割するため、
         * Phase 2の検出ロジックが機能しない（実測補正率0%）
         * Phase 1のみで十分な精度（57-85%）を達成できる
         */
        private const val ENABLE_PHASE2 = false

        /**
         * v1.0.39: LLM補正の有効化フラグ
         * v1.0.40: Gemini API統合により有効化
         */
        private const val ENABLE_LLM_CORRECTION = true

        /**
         * v1.0.39: CorrectionValidator有効化フラグ
         */
        private const val ENABLE_VALIDATION = true

        /**
         * v1.0.39: LLM補正を試行する信頼度閾値
         * Phase 1の信頼度がこれ未満の場合、LLM補正を試行
         * v1.1.5: 0.7 → 0.5に変更（より厳しく、LLM API呼び出しを削減）
         * v1.1.26: 0.5 → 1.0に変更（全文LLM補正モード - テスト用）
         * 理由: OCR誤認識「龍の要」→「需要」などをLLMで補正するため
         * 期待効果: +20%認識率向上
         */
        private const val MIN_CONFIDENCE_FOR_PHASE1 = 2.0f  // v1.1.27: 全文LLM補正モード（1.0では信頼度1.0でスキップされるため2.0に）

        // Phase 2: 形態素解析器（遅延初期化）
        private val morphAnalyzer: MorphologicalAnalyzer by lazy {
            MorphologicalAnalyzer()
        }

        // Phase 3: 信頼度解析器（遅延初期化）
        private val confidenceAnalyzer: ConfidenceAnalyzer by lazy {
            ConfidenceAnalyzer()
        }

        // Phase 3: UniDic助詞脱落検出器（遅延初期化） - v1.0.64
        private val particleDetector: ParticleMissingDetector by lazy {
            ParticleMissingDetector()
        }

        // Phase 3: UniDic送り仮名補正器（遅延初期化） - v1.0.65
        private val okuriganaCorrector: OkuriganaCorrector by lazy {
            OkuriganaCorrector()
        }

        // Phase 3: 促音・長音補正器（遅延初期化） - v1.0.66
        private val sokuonChoonCorrector: SokuonChoonCorrector by lazy {
            SokuonChoonCorrector()
        }

        // Phase 3: 漢字字形類似誤認識補正器（遅延初期化） - v1.0.68
        private val kanjiShapeCorrector: KanjiShapeCorrector by lazy {
            KanjiShapeCorrector()
        }

        // v1.1.33: 自動学習の有効化フラグ
        private const val ENABLE_AUTO_LEARN = true

        // Phase 3制御フラグ
        private const val ENABLE_PARTICLE_DETECTION = true       // v1.0.64: 助詞脱落検出
        private const val ENABLE_OKURIGANA_CORRECTION = false    // v1.0.69: 一時的に無効化（プロセスクラッシュ問題）
        private const val ENABLE_SOKUON_CHOON_CORRECTION = false // v1.0.69: 一時的に無効化（OOM問題）
        private const val ENABLE_KANJI_SHAPE_CORRECTION = false  // v1.0.70: 一時的に無効化（OOM問題）

        /**
         * Phase 1: パターンベース補正ルール (v1.0.31, v1.0.38大幅拡張, v1.0.39順序最適化)
         * 類似漢字グループを使った一般化パターン
         * 目標: 経済用語150語以上カバー、補正率85-90%
         *
         * v1.0.39最適化:
         * - 頻出パターンを上位に配置（実測データに基づく）
         * - 処理時間: 20-30%削減予想
         */
        private val generalizedPatterns = listOf(
            // ============================================================
            // 最頻出パターン（Top 10）- v1.0.35/v1.0.36実機テストで確認
            // ============================================================

            // 1. 需要パターン（最頻出）
            GeneralizedRule(
                pattern = Regex("[講書霜艦需能][要婁解]"),
                correct = "需要",
                description = "需要の誤認識"
            ),

            // 2. 価格パターン（最頻出）
            GeneralizedRule(
                pattern = Regex("[再価洒偏海済梅恒順福][格将終稿]"),
                correct = "価格",
                description = "価格の誤認識"
            ),

            // 3. 経済パターン（頻出）
            GeneralizedRule(
                pattern = Regex("[経稲雑][演済潜]"),
                correct = "経済",
                description = "経済の誤認識"
            ),

            // 4. 経済学パターン（頻出）
            GeneralizedRule(
                pattern = Regex("[経稲雑][演済潜][学単]"),
                correct = "経済学",
                description = "経済学の誤認識"
            ),

            // 5. 供給パターン（頻出）
            GeneralizedRule(
                pattern = Regex("[供共][靖給絵紛]"),
                correct = "供給",
                description = "供給の誤認識"
            ),

            // 6. 市場パターン（頻出）
            GeneralizedRule(
                pattern = Regex("[斉市肯][場堵揚]"),
                correct = "市場",
                description = "市場の誤認識"
            ),

            // 7. 効果パターン（頻出）
            GeneralizedRule(
                pattern = Regex("[祝勅効勃課][歌果呆]"),
                correct = "効果",
                description = "効果の誤認識"
            ),

            // 8. 弾力性パターン（頻出）
            GeneralizedRule(
                pattern = Regex("[無弾単][力刀][性住]"),
                correct = "弾力性",
                description = "弾力性の誤認識"
            ),

            // 9. 影響パターン（頻出）
            GeneralizedRule(
                pattern = Regex("[絵影][響喬]"),
                correct = "影響",
                description = "影響の誤認識"
            ),

            // 10. 法則パターン（頻出）
            GeneralizedRule(
                pattern = Regex("[法洪][則測貝]"),
                correct = "法則",
                description = "法則の誤認識"
            ),

            // ============================================================
            // 基本経済用語（残り）
            // ============================================================

            // 値段パターン
            GeneralizedRule(
                pattern = Regex("[植催値]段"),
                correct = "値段",
                description = "値段の誤認識"
            ),

            // 値札パターン
            GeneralizedRule(
                pattern = Regex("[植催値]札"),
                correct = "値札",
                description = "値札の誤認識"
            ),

            // 値引きパターン
            GeneralizedRule(
                pattern = Regex("[植催値][引弓]き"),
                correct = "値引き",
                description = "値引きの誤認識"
            ),

            // 割引パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[新都割刻][引弓]"),
                correct = "割引",
                description = "割引の誤認識"
            ),

            // ============================================================
            // マクロ経済学用語
            // ============================================================

            // 経済パターン（拡張）
            GeneralizedRule(
                pattern = Regex("[経稲雑][演済潜]"),
                correct = "経済",
                description = "経済の誤認識"
            ),

            // 経済学パターン（拡張）
            GeneralizedRule(
                pattern = Regex("[経稲雑][演済潜][学単]"),
                correct = "経済学",
                description = "経済学の誤認識"
            ),

            // 景気パターン（v1.0.38追加, v1.1.31: 環→景追加）
            GeneralizedRule(
                pattern = Regex("[景影京環][気氛]"),
                correct = "景気",
                description = "景気の誤認識"
            ),

            // 不況パターン（v1.0.38追加, v1.1.31: 死→況追加）
            GeneralizedRule(
                pattern = Regex("[不木][況洗死]"),
                correct = "不況",
                description = "不況の誤認識"
            ),

            // 好況パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[好妤][況洗]"),
                correct = "好況",
                description = "好況の誤認識"
            ),

            // インフレパターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("イン[フブ][レし]"),
                correct = "インフレ",
                description = "インフレの誤認識"
            ),

            // デフレパターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[デテ][フブ][レし]"),
                correct = "デフレ",
                description = "デフレの誤認識"
            ),

            // 物価パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[物勿][価再洒]"),
                correct = "物価",
                description = "物価の誤認識"
            ),

            // 金利パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[金釜][利和]"),
                correct = "金利",
                description = "金利の誤認識"
            ),

            // 財政パターン（v1.0.38追加, v1.1.31: 証,競追加）
            GeneralizedRule(
                pattern = Regex("[財柑証競][政改]"),
                correct = "財政",
                description = "財政の誤認識"
            ),

            // 税金パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[税祝][金釜]"),
                correct = "税金",
                description = "税金の誤認識"
            ),

            // 所得パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[所断][得待]"),
                correct = "所得",
                description = "所得の誤認識"
            ),

            // 国債パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[国固囲][債僧]"),
                correct = "国債",
                description = "国債の誤認識"
            ),

            // 赤字パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[赤亦][字宇]"),
                correct = "赤字",
                description = "赤字の誤認識"
            ),

            // 黒字パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[黒里墨][字宇]"),
                correct = "黒字",
                description = "黒字の誤認識"
            ),

            // 通貨パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[通迫][貨貝]"),
                correct = "通貨",
                description = "通貨の誤認識"
            ),

            // 貨幣パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[貨貝][幣常]"),
                correct = "貨幣",
                description = "貨幣の誤認識"
            ),

            // ============================================================
            // ミクロ経済学用語
            // ============================================================

            // 効果パターン（拡張）
            GeneralizedRule(
                pattern = Regex("[祝勅効勃課][歌果呆]"),
                correct = "効果",
                description = "効果の誤認識"
            ),

            // 弾力性パターン（拡張）
            GeneralizedRule(
                pattern = Regex("[無弾単][力刀][性住]"),
                correct = "弾力性",
                description = "弾力性の誤認識"
            ),

            // 競争パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[競兢][争事]"),
                correct = "競争",
                description = "競争の誤認識"
            ),

            // 独占パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[独猫][占古]"),
                correct = "独占",
                description = "独占の誤認識"
            ),

            // 寡占パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[寡募][占古]"),
                correct = "寡占",
                description = "寡占の誤認識"
            ),

            // 限界パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[限眼][界堺]"),
                correct = "限界",
                description = "限界の誤認識"
            ),

            // 費用パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[費贅][用甩]"),
                correct = "費用",
                description = "費用の誤認識"
            ),

            // 収入パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[収牧][入λ]"),
                correct = "収入",
                description = "収入の誤認識"
            ),

            // 利潤パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[利和][潤閏]"),
                correct = "利潤",
                description = "利潤の誤認識"
            ),

            // 損失パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[損員][失矢]"),
                correct = "損失",
                description = "損失の誤認識"
            ),

            // 消費パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[消清][費贅]"),
                correct = "消費",
                description = "消費の誤認識"
            ),

            // 生産パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[生主][産崖]"),
                correct = "生産",
                description = "生産の誤認識"
            ),

            // ============================================================
            // 貿易・国際経済
            // ============================================================

            // 輸出パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[輸愉][出山]"),
                correct = "輸出",
                description = "輸出の誤認識"
            ),

            // 輸入パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[輸愉][入λ]"),
                correct = "輸入",
                description = "輸入の誤認識"
            ),

            // 貿易パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[貿貫][易昌]"),
                correct = "貿易",
                description = "貿易の誤認識"
            ),

            // 関税パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[関開][税祝]"),
                correct = "関税",
                description = "関税の誤認識"
            ),

            // 為替パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[為烏][替曹]"),
                correct = "為替",
                description = "為替の誤認識"
            ),

            // ============================================================
            // その他頻出用語
            // ============================================================

            // 期待パターン
            GeneralizedRule(
                pattern = Regex("[舞期][待持]"),
                correct = "期待",
                description = "期待の誤認識"
            ),

            // 影響パターン
            GeneralizedRule(
                pattern = Regex("[絵影][響喬]"),
                correct = "影響",
                description = "影響の誤認識"
            ),

            // 土地パターン
            GeneralizedRule(
                pattern = Regex("[士土][地坪]"),
                correct = "土地",
                description = "土地の誤認識"
            ),

            // 学問パターン
            GeneralizedRule(
                pattern = Regex("[学単][間問]"),
                correct = "学問",
                description = "学問の誤認識"
            ),

            // 投資パターン（拡張）
            GeneralizedRule(
                pattern = Regex("[投授][育質資贅]"),
                correct = "投資",
                description = "投資の誤認識"
            ),

            // 市場パターン（拡張）
            GeneralizedRule(
                pattern = Regex("[斉市肯][場堵揚]"),
                correct = "市場",
                description = "市場の誤認識"
            ),

            // 法則パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[法洪][則測貝]"),
                correct = "法則",
                description = "法則の誤認識"
            ),

            // 理論パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[理埋][論輪]"),
                correct = "理論",
                description = "理論の誤認識"
            ),

            // 政府パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[政改][府庁]"),
                correct = "政府",
                description = "政府の誤認識"
            ),

            // 企業パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[企全][業亘]"),
                correct = "企業",
                description = "企業の誤認識"
            ),

            // 家計パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[家宅][計針]"),
                correct = "家計",
                description = "家計の誤認識"
            ),

            // ============================================================
            // 動詞・形容詞パターン
            // ============================================================

            // 増えるパターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[増噌]える"),
                correct = "増える",
                description = "増えるの誤認識"
            ),

            // 減るパターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[減械]る"),
                correct = "減る",
                description = "減るの誤認識"
            ),

            // 減らすパターン
            GeneralizedRule(
                pattern = Regex("[械減]らす"),
                correct = "減らす",
                description = "減らすの誤認識"
            ),

            // 上がるパターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[上止]がる"),
                correct = "上がる",
                description = "上がるの誤認識"
            ),

            // 下がるパターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[下卞]がる"),
                correct = "下がる",
                description = "下がるの誤認識"
            ),

            // 高いパターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[高寓]い"),
                correct = "高い",
                description = "高いの誤認識"
            ),

            // 安いパターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[安妾]い"),
                correct = "安い",
                description = "安いの誤認識"
            ),

            // 大きいパターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[大犬]きい"),
                correct = "大きい",
                description = "大きいの誤認識"
            ),

            // 小さいパターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[小少]さい"),
                correct = "小さい",
                description = "小さいの誤認識"
            ),

            // 良いパターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[良艮]い"),
                correct = "良い",
                description = "良いの誤認識"
            ),

            // 悪いパターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[悪惑]い"),
                correct = "悪い",
                description = "悪いの誤認識"
            ),

            // 狭いパターン
            GeneralizedRule(
                pattern = Regex("[挟狭]い"),
                correct = "狭い",
                description = "狭いの誤認識"
            ),

            // 買うパターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[買真買]う"),
                correct = "買う",
                description = "買うの誤認識"
            ),

            // 買いたいパターン
            GeneralizedRule(
                pattern = Regex("[真買]?[い]?たい"),
                correct = "買いたい",
                description = "買いたいの誤認識"
            ),

            // 売るパターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[売亮]る"),
                correct = "売る",
                description = "売るの誤認識"
            ),

            // ============================================================
            // 助詞・方向パターン
            // ============================================================

            // 方向（～の方）パターン（v1.0.38追加）
            GeneralizedRule(
                pattern = Regex("[方万万]に"),
                correct = "方に",
                description = "方にの誤認識"
            ),

            // ============================================================
            // その他（カタカナ、環境、消費財）
            // ============================================================

            // 酒類パターン
            GeneralizedRule(
                pattern = Regex("酒[頼瀬類]"),
                correct = "酒類",
                description = "酒類の誤認識"
            ),

            // ポリ袋パターン
            GeneralizedRule(
                pattern = Regex("[ボポ]り袋"),
                correct = "ポリ袋",
                description = "ポリ袋の誤認識"
            ),

            // ポリエチレンパターン
            GeneralizedRule(
                pattern = Regex("[ボポ]リエチレン"),
                correct = "ポリエチレン",
                description = "ポリエチレンの誤認識"
            ),

            // 使い捨てパターン（v1.0.31修正: 正しいテキストを除外）
            GeneralizedRule(
                pattern = Regex("(?<!使)い捨て|使い拾て"),
                correct = "使い捨て",
                description = "使い捨ての誤認識"
            ),

            // ハッピーアワーパターン
            GeneralizedRule(
                pattern = Regex("[くハ][ッツ][ピヒ][ー]?[アマ]ワー"),
                correct = "ハッピーアワー",
                description = "ハッピーアワーの誤認識"
            ),

            // プライムパターン
            GeneralizedRule(
                pattern = Regex("[ブプ]ライ[ム厶]"),
                correct = "プライム",
                description = "プライムの誤認識"
            ),

            // 環境への悪パターン
            GeneralizedRule(
                pattern = Regex("環境[くへ]の[悪惑]"),
                correct = "環境への悪",
                description = "環境への悪の誤認識"
            ),

            // ============================================================
            // 数字パターン（v1.1.1新規追加）
            // ============================================================

            // 数字1の誤認識（l, I, |）
            GeneralizedRule(
                pattern = Regex("(?<=[^a-zA-Z])[lI|](?=[^a-zA-Z])"),
                correct = "1",
                description = "数字1の誤認識"
            ),

            // 数字0の誤認識（O, o）
            GeneralizedRule(
                pattern = Regex("(?<=[0-9])[Oo](?=[0-9])"),
                correct = "0",
                description = "数字0の誤認識"
            ),

            // 数字5の誤認識（S）
            GeneralizedRule(
                pattern = Regex("(?<=[0-9])S(?=[0-9])"),
                correct = "5",
                description = "数字5の誤認識"
            ),

            // ============================================================
            // v1.1.13: 実機ログから検出された新規誤認識パターン
            // ============================================================

            // 宿題の誤認識（有題）
            GeneralizedRule(
                pattern = Regex("有題"),
                correct = "宿題",
                description = "宿題の誤認識"
            ),

            // 知識の誤認識（知無）
            GeneralizedRule(
                pattern = Regex("知無"),
                correct = "知識",
                description = "知識の誤認識"
            ),

            // 推奨の誤認識（推製）
            GeneralizedRule(
                pattern = Regex("推製"),
                correct = "推奨",
                description = "推奨の誤認識"
            ),

            // 国家の誤認識（印家）
            GeneralizedRule(
                pattern = Regex("印家"),
                correct = "国家",
                description = "国家の誤認識"
            ),

            // 危機の誤認識（華梨）
            GeneralizedRule(
                pattern = Regex("華梨"),
                correct = "危機",
                description = "危機の誤認識"
            ),

            // 経済の重複文字（経済済）
            GeneralizedRule(
                pattern = Regex("経済済"),
                correct = "経済",
                description = "経済の重複文字"
            ),

            // 基本の不完全認識（基本定）
            GeneralizedRule(
                pattern = Regex("基本定"),
                correct = "基本",
                description = "基本の不完全認識"
            ),

            // 年代表記の数字混入（1一）
            GeneralizedRule(
                pattern = Regex("1一"),
                correct = "一",
                description = "年代表記の数字混入"
            ),

            // 10の誤認識（11〇, 1〇）
            GeneralizedRule(
                pattern = Regex("11〇"),
                correct = "10",
                description = "10%の誤認識"
            ),

            // インフレの不要なスペース
            GeneralizedRule(
                pattern = Regex("イン\\s+フレ"),
                correct = "インフレ",
                description = "インフレのスペース誤認識"
            ),

            // 苦しめられたの誤認識（苦しゆられた）
            GeneralizedRule(
                pattern = Regex("苦しゆられた"),
                correct = "苦しめられた",
                description = "苦しめられたの誤認識"
            ),

            // ============================================================
            // v1.1.31: 実機テストv1.1.30で検出された新規OCR誤認識パターン
            // ============================================================

            // 刺激パターン（瀬→激の誤認識）
            GeneralizedRule(
                pattern = Regex("刺[瀬激撃]"),
                correct = "刺激",
                description = "刺激の誤認識"
            ),

            // 刺激効果（複合パターン: 刺瀬数果→刺激効果）
            GeneralizedRule(
                pattern = Regex("刺[瀬激][数効][果歌]"),
                correct = "刺激効果",
                description = "刺激効果の誤認識"
            ),

            // 景気刺激（複合パターン）
            GeneralizedRule(
                pattern = Regex("[景影京環][気氛]刺[瀬激]"),
                correct = "景気刺激",
                description = "景気刺激の誤認識"
            ),

            // 時間パターン（持→時の誤認識）
            GeneralizedRule(
                pattern = Regex("持[間問]"),
                correct = "時間",
                description = "時間の誤認識"
            ),

            // 乗数パターン（乗務教→乗数）
            GeneralizedRule(
                pattern = Regex("乗[務数][教数]?"),
                correct = "乗数",
                description = "乗数の誤認識"
            ),

            // 持続パターン（時続→持続）
            GeneralizedRule(
                pattern = Regex("[時持][続読]"),
                correct = "持続",
                description = "持続の誤認識"
            ),

            // 実際パターン（美際→実際）
            GeneralizedRule(
                pattern = Regex("[美実][際祭]"),
                correct = "実際",
                description = "実際の誤認識"
            ),

            // 克服パターン（完服→克服）
            GeneralizedRule(
                pattern = Regex("[完克][服腹]"),
                correct = "克服",
                description = "克服の誤認識"
            ),

            // 激論パターン（激舗→激論）
            GeneralizedRule(
                pattern = Regex("[激撃][舗論輪]"),
                correct = "激論",
                description = "激論の誤認識"
            ),

            // 長期的パターン（長期約→長期的）
            GeneralizedRule(
                pattern = Regex("長期[約的]"),
                correct = "長期的",
                description = "長期的の誤認識"
            ),

            // 問題点パターン（問題京→問題点）
            GeneralizedRule(
                pattern = Regex("問題[京点]"),
                correct = "問題点",
                description = "問題点の誤認識"
            ),

            // 金融政策パターン（金融政第→金融政策）
            GeneralizedRule(
                pattern = Regex("金融政[第策]"),
                correct = "金融政策",
                description = "金融政策の誤認識"
            ),

            // 流動性の罠パターン（流動性の覧→流動性の罠）
            GeneralizedRule(
                pattern = Regex("流動性の[覧罠]"),
                correct = "流動性の罠",
                description = "流動性の罠の誤認識"
            ),

            // 孤島パターン（小島→孤島 in economic context is usually 孤島）
            // Note: 小島 can be valid (small island), but in economics context 孤島 is more common
            // This is handled by dictionary instead for safety

            // 概念パターン（念→概念 の断片対応）
            GeneralizedRule(
                pattern = Regex("という[念概]"),
                correct = "という概念",
                description = "概念の断片誤認識"
            )
        )

        /**
         * 一般化ルールのデータクラス
         */
        data class GeneralizedRule(
            val pattern: Regex,
            val correct: String,
            val description: String
        )

        /**
         * 経済用語の誤認識辞書（Phase 1補完, v1.0.38大幅拡張）
         * generalizedPatternsでカバーできない特定パターンを補正
         */
        private val economicTermsDict = mapOf(
            // ============================================================
            // 需要・供給・価格
            // ============================================================

            // 需要関連
            "講要" to "需要",
            "書要" to "需要",
            "霜要" to "需要",
            "需婁" to "需要",
            "霜婁" to "需要",
            "艦要" to "需要",
            "能要" to "需要",  // v1.0.35実機テスト

            // 供給関連
            "供靖" to "供給",
            "共給" to "供給",
            "供絵" to "供給",
            "供紛" to "供給",

            // 価格関連
            "再将" to "価格",
            "価将" to "価格",
            "洒格" to "価格",
            "偏格" to "価格",
            "海格" to "価格",
            "梅格" to "価格",  // v1.0.35実機テスト
            "恒終" to "価格",  // v1.0.35実機テスト
            "済終" to "価格",

            // 値段・値札・値引き
            "植段" to "値段",
            "植札" to "値札",
            "催引き" to "値引き",
            "値弓き" to "値引き",
            "催段" to "値段",

            // 割引関連
            "都引" to "割引",  // v1.0.35実機テスト
            "新引" to "割引",
            "刻引" to "割引",
            "新記引対豪" to "割引対象",  // v1.0.35実機テスト
            "都引対豪" to "割引対象",
            "割引対家" to "割引対象",

            // ============================================================
            // マクロ経済学用語
            // ============================================================

            // 経済関連
            "経演" to "経済",
            "経潜" to "経済",
            "雑済" to "経済",
            "稲済" to "経済",
            "経演学" to "経済学",
            "雑済学" to "経済学",
            "稲済学" to "経済学",

            // 景気関連
            "景氛" to "景気",
            "影気" to "景気",
            "京気" to "景気",

            // 不況・好況
            "不洗" to "不況",
            "木況" to "不況",
            "好洗" to "好況",
            "妤況" to "好況",

            // インフレ・デフレ
            "インブレ" to "インフレ",
            "インフし" to "インフレ",
            "テフレ" to "デフレ",
            "デブレ" to "デフレ",
            "デフし" to "デフレ",

            // 物価・金利
            "勿価" to "物価",
            "物再" to "物価",
            "物洒" to "物価",
            "釜利" to "金利",
            "金和" to "金利",

            // 財政・税金
            "柑政" to "財政",
            "財改" to "財政",
            "祝金" to "税金",
            "税釜" to "税金",

            // 所得・国債
            "断得" to "所得",
            "所待" to "所得",
            "固債" to "国債",
            "囲債" to "国債",
            "国僧" to "国債",

            // 赤字・黒字
            "亦字" to "赤字",
            "赤宇" to "赤字",
            "里字" to "黒字",
            "墨字" to "黒字",
            "黒宇" to "黒字",

            // 通貨・貨幣
            "迫貨" to "通貨",
            "通貝" to "通貨",
            "貝幣" to "貨幣",
            "貨常" to "貨幣",

            // ============================================================
            // ミクロ経済学用語
            // ============================================================

            // 効果関連
            "祝歌" to "効果",
            "勅果" to "効果",
            "効呆" to "効果",
            "勃果" to "効果",
            "課果" to "効果",
            "課歌" to "効果",
            "代替祝歌" to "代替効果",
            "代替勅果" to "代替効果",
            "所得祝歌" to "所得効果",
            "所得勅果" to "所得効果",

            // 弾力性関連
            "無力性" to "弾力性",
            "単力性" to "弾力性",
            "弾刀性" to "弾力性",
            "弾力住" to "弾力性",
            "価格無力性" to "価格弾力性",
            "需要の無力性" to "需要の弾力性",

            // 競争・独占・寡占
            "兢争" to "競争",
            "競事" to "競争",
            "猫占" to "独占",
            "独古" to "独占",
            "募占" to "寡占",
            "寡古" to "寡占",
            "完全兢争" to "完全競争",
            "完全競事" to "完全競争",

            // 限界・費用
            "眼界" to "限界",
            "限堺" to "限界",
            "贅用" to "費用",
            "費甩" to "費用",
            "限眼費用" to "限界費用",
            "限堺費用" to "限界費用",

            // 収入・利潤・損失
            "牧入" to "収入",
            "収λ" to "収入",
            "和潤" to "利潤",
            "利閏" to "利潤",
            "員失" to "損失",
            "損矢" to "損失",

            // 消費・生産
            "清費" to "消費",
            "消贅" to "消費",
            "主産" to "生産",
            "生崖" to "生産",

            // ============================================================
            // 貿易・国際経済
            // ============================================================

            // 輸出・輸入・貿易
            "愉出" to "輸出",
            "輸山" to "輸出",
            "愉入" to "輸入",
            "輸λ" to "輸入",
            "貫易" to "貿易",
            "貿昌" to "貿易",

            // 関税・為替
            "開税" to "関税",
            "関祝" to "関税",
            "烏替" to "為替",
            "為曹" to "為替",

            // ============================================================
            // その他頻出用語
            // ============================================================

            // 期待・影響
            "舞待" to "期待",
            "期持" to "期待",
            "絵響" to "影響",
            "影喬" to "影響",

            // 学問・投資・市場
            "学間" to "学問",
            "単問" to "学問",
            "投育" to "投資",
            "投質" to "投資",
            "投贅" to "投資",
            "授資" to "投資",
            "斉場" to "市場",
            "市堵" to "市場",
            "肯場" to "市場",
            "市揚" to "市場",

            // 法則・理論
            "洪則" to "法則",
            "法測" to "法則",
            "法貝" to "法則",
            "埋論" to "理論",
            "理輪" to "理論",

            // 政府・企業・家計
            "改府" to "政府",
            "政庁" to "政府",
            "全業" to "企業",
            "企亘" to "企業",
            "宅計" to "家計",
            "家針" to "家計",

            // 土地・酒類
            "士地" to "土地",
            "土坪" to "土地",
            "酒頼" to "酒類",
            "酒瀬" to "酒類",

            // ============================================================
            // 動詞・形容詞の特殊ケース
            // ============================================================

            // 減らす・買いたい
            "械らす" to "減らす",
            "真いたい" to "買いたい",
            "買たい" to "買いたい",
            "真う" to "買う",

            // 増える・減る
            "噌える" to "増える",
            "械る" to "減る",

            // 高い・安い
            "寓い" to "高い",
            "妾い" to "安い",
            "安いはうに" to "安い方に",  // v1.0.35実機テスト
            "安いはう" to "安い方",
            "寓い方" to "高い方",

            // 大きい・小さい
            "犬きい" to "大きい",
            "少さい" to "小さい",

            // 良い・悪い
            "艮い" to "良い",
            "惑い" to "悪い",

            // 上がる・下がる
            "止がる" to "上がる",
            "卞がる" to "下がる",

            // ============================================================
            // 割引対象の複合パターン
            // ============================================================

            // 対象関連
            "対豪" to "対象",
            "対家" to "対象",
            "割引対家" to "割引対象",

            // ============================================================
            // v1.1.31: 実機テストv1.1.30で検出された新規エントリ
            // ============================================================

            // 景気・刺激関連
            "環気" to "景気",
            "刺瀬" to "刺激",
            "数果" to "効果",
            "刺瀬数果" to "刺激効果",

            // 時間・持続関連
            "持間" to "時間",
            "時続" to "持続",

            // 財政・乗数関連
            "証政" to "財政",
            "乗務教" to "乗数",
            "乗務" to "乗数",

            // 不況関連
            "不死" to "不況",

            // 一般用語
            "完服" to "克服",
            "美際" to "実際",
            "問題京" to "問題点",
            "金融政第" to "金融政策",
            "激舗" to "激論",
            "長期約" to "長期的",
            "真献" to "貢献",

            // 流動性の罠
            "流動性の覧" to "流動性の罠",

            // OCRノイズ文字除去パターン
            "こ和ら" to "これら",
            "ししまう" to "しまう",
            "むみに" to "むやみに",

            // OCR文字分裂・ノイズ挿入パターン
            "す文出" to "支出",       // 支→す文 に分裂
            "下回国る" to "下回る",   // 国がノイズ挿入
            "上がN" to "上がる",      // Nがノイズ
            "こRでは" to "ここでは",  // Rがノイズ

            // ============================================================
            // v1.1.31: 実機テストv1.1.30bで検出された新規OCR誤認識パターン
            // ============================================================

            // 財政関連（競→財の誤認識）
            "競政政策" to "財政政策",  // 複合パターン優先
            "競政" to "財政",

            // 日本関連（旧→日の誤認識）
            "旧本" to "日本",         // 旧(きゅう)と日(にち)の形状類似

            // 建物・施設関連
            "方舎" to "庁舎",         // 方→庁の誤認識
            "邪校" to "学校",         // 邪→学の誤認識

            // 庁舎・学校の複合パターン
            "方舎·邪校" to "庁舎・学校"  // ·(中点)も正規化
        )

        /**
         * カタカナの誤認識パターン
         * 長音記号や小文字の誤認識を修正
         */
        private val katakanaPatterns = mapOf(
            // ハッピーアワー系
            "くッピーマワー" to "ハッピーアワー",
            "くッピー" to "ハッピー",
            "マワー" to "アワー",
            "ハツピー" to "ハッピー",
            "ハッヒー" to "ハッピー",

            // プライム系
            "ブライム" to "プライム",
            "プライ厶" to "プライム",
            "ブライ厶" to "プライム",

            // エコー系
            "エコ1" to "エコー",
            "エコ|" to "エコー",

            // その他頻出カタカナ
            "スマ1ト" to "スマート",
            "スマート" to "スマート",  // 正しいもの確認
            "スピ1カ1" to "スピーカー",
            "スピーカー" to "スピーカー",  // 正しいもの確認
            "アマゾン" to "アマゾン",  // 正しいもの確認
            "デ1" to "デー",
            "テ1" to "テー",

            // ポリ関連（v1.0.30追加）
            "ボり袋" to "ポリ袋",
            "ボリ袋" to "ポリ袋",
            "ボリエチレン" to "ポリエチレン",

            // 環境関連
            "環境くの悪" to "環境への悪",
            "環境への惑" to "環境への悪"
        )

        /**
         * 文脈依存の補正パターン (v1.0.38拡張)
         * 前後の文字から判断して補正
         */
        private val contextualPatterns = listOf(
            // ============================================================
            // 「～の法則」「～の理論」パターン
            // ============================================================

            Regex("(講要|書要|霜要|艦要|能要)の[法洪](則|測|貝)") to "需要の法則",
            Regex("(講要|書要|霜要|艦要|能要)と(供給|共給|供靖)") to "需要と供給",
            Regex("(講要|書要|霜要|艦要|能要)の(無力性|弾刀性|単力性)") to "需要の弾力性",

            // ============================================================
            // 価格関連の文脈パターン
            // ============================================================

            Regex("(再将|価将|洒格|海格|梅格|恒終|済終)が") to "価格が",
            Regex("(再将|価将|洒格|海格|梅格|恒終|済終)の") to "価格の",
            Regex("(再将|価将|洒格|海格|梅格|恒終|済終)は") to "価格は",
            Regex("(再将|価将|洒格|海格|梅格|恒終|済終)を") to "価格を",
            Regex("(再将|価将|洒格|海格|梅格|恒終|済終)に") to "価格に",
            Regex("(再将|価将|洒格|海格)(無力性|弾刀性|単力性)") to "価格弾力性",
            Regex("(再将|価将|洒格|海格)の(無力性|弾刀性)") to "価格の弾力性",

            // ============================================================
            // 効果関連パターン
            // ============================================================

            Regex("(祝歌|勅果|効呆|課果)(\\(income effect\\))") to "効果$2",
            Regex("(祝歌|勅果|効呆|課果)(\\(substitution effect\\))") to "効果$2",
            Regex("(所得|断得)(祝歌|勅果|課果)") to "所得効果",
            Regex("代替(祝歌|勅果|効呆|課果)") to "代替効果",
            Regex("(祝歌|勅果|効呆|課果)がある") to "効果がある",
            Regex("(祝歌|勅果|効呆|課果)を") to "効果を",

            // ============================================================
            // 経済学・市場関連パターン
            // ============================================================

            Regex("(経演|経潜|雑済|稲済)[学単]") to "経済学",
            Regex("[マミ]クロ(経演|経潜|雑済)[学単]") to "ミクロ経済学",
            Regex("[マミ][ククウ]ロ(経演|経潜|雑済)[学単]") to "マクロ経済学",
            Regex("(斉場|市堵|肯場|市揚)の") to "市場の",
            Regex("(斉場|市堵|肯場|市揚)で") to "市場で",
            Regex("(斉場|市堵|肯場|市揚)に") to "市場に",
            Regex("完全(兢争|競事)(斉場|市堵)") to "完全競争市場",

            // ============================================================
            // 数字・年号パターン
            // ============================================================

            Regex("一\\s*○\\s*ボン") to "一〇ドル",
            Regex("([0-9]+)\\s*O\\s*([0-9]+)円") to "$1\u0030$2円",  // 0を正しく補正
            Regex("([0-9]+)\\s*円") to "$1円",  // スペース削除
            Regex("1\\s*杯") to "一杯",
            Regex("二O1○年") to "2010年",
            Regex("二〇一〇年") to "2010年",
            Regex("([0-9]{2,3})O([0-9]{1,2})年") to "$1\u0030$2年",  // OをO(ゼロ)に
            Regex("([0-9]{4})([年月日])") to "$1$2",  // 年月日の正規化

            // ============================================================
            // 助詞の誤認識パターン
            // ============================================================

            Regex("(需要|供給|価格|市場)ガ") to "$1が",
            Regex("(需要|供給|価格|市場)ノ") to "$1の",
            Regex("(需要|供給|価格|市場)ヲ") to "$1を",
            Regex("(需要|供給|価格|市場)ニ") to "$1に",
            Regex("(需要|供給|価格|市場)ハ") to "$1は",
            Regex("(需要|供給|価格|市場)ヘ") to "$1へ",
            Regex("(需要|供給|価格|市場)ト") to "$1と",

            // ============================================================
            // 酒類・商品関連
            // ============================================================

            Regex("(酒頼|酒瀬)の") to "酒類の",
            Regex("(酒頼|酒瀬)を") to "酒類を",
            Regex("(酒頼|酒瀬)に") to "酒類に",
            Regex("([ボポ]り袋|[ボポ]リエチレン)の") to "ポリ袋の",

            // ============================================================
            // 複合語パターン
            // ============================================================

            Regex("(国固囲)(債僧)") to "国債",
            Regex("(財柑)(政改)") to "財政",
            Regex("(金釜)(利和)") to "金利",
            Regex("(通迫)(貨貝)") to "通貨",
            Regex("([赤亦]字|[黒里墨]字)の") to "赤字の",
            Regex("[愉][出]") to "輸出",
            Regex("[愉][入]") to "輸入",

            // ============================================================
            // 動詞・形容詞の文脈パターン
            // ============================================================

            Regex("([高寓])くなる") to "高くなる",
            Regex("([安妾])くなる") to "安くなる",
            Regex("([高寓])まる") to "高まる",
            Regex("([増噌])える") to "増える",
            Regex("([減械])る") to "減る",
            Regex("([買真])いたい") to "買いたい",
            Regex("([売亮])れる") to "売れる",

            // ============================================================
            // 割引・対象パターン
            // ============================================================

            Regex("([新都刻]引|[新都刻][引弓])対(豪|家)") to "割引対象",
            Regex("([新都刻]引)の") to "割引の",
            Regex("([新都刻]引)を") to "割引を",
            Regex("対(豪|家)と") to "対象と",

            // ============================================================
            // その他頻出パターン
            // ============================================================

            Regex("([期舞]待|[影絵]響)を") to "$1を",
            Regex("([期舞]待|[影絵]響)が") to "$1が",
            Regex("([限眼][界堺])(費贅用|[費贅][用甩])") to "限界費用",
            Regex("([完宗]全)(兢争|競事)") to "完全競争",
            Regex("([独猫][占古]|[寡募][占古])") to "独占"
        )
    }  // companion object終了

    // ============================================================
    // v1.0.39: インスタンス変数（LLM補正とバリデーション用）
    // ============================================================

    // LLM補正器（遅延初期化）
    private val llmCorrector: LLMCorrector by lazy {
        LLMCorrector(context)
    }

    // 補正バリデーター（遅延初期化）
    private val correctionValidator: CorrectionValidator by lazy {
        CorrectionValidator()
    }

    // v1.1.32: 文字種異常検出補正器（Stage 1: 安全な直接補正）
    private val charTypeAnomalyCorrector: CharTypeAnomalyCorrector by lazy {
        CharTypeAnomalyCorrector()
    }

    // v1.1.32: 形状類似ヒントビルダー（Stage 2: LLMヒント生成）
    private val shapeSimilarHintBuilder: ShapeSimilarHintBuilder by lazy {
        ShapeSimilarHintBuilder()
    }

    // v1.1.33: 自動学習マネージャー（遅延初期化）
    private val autoLearnManager: AutoLearnManager by lazy {
        AutoLearnManager.getInstance(context)
    }

    // Phase 1で適用されたパターンを追跡
    private val appliedPatterns = mutableListOf<String>()

    // v1.1.41: LLM使用状況の外部公開（OverlayServiceがステータス表示に使用）
    @Volatile var lastCorrectionUsedLLM = false
        private set

    // v1.1.41: ユーザー修正後に次ページのCleanPageスキップを無効化するフラグ
    @Volatile private var forceFullCorrectionOnce = false

    /**
     * v1.1.41: ユーザーがロングタップ修正を行った後に呼ぶ。
     * 次回のcorrectText()でCleanPageゲート（優先2/3）をバイパスし、LLMを強制実行する。
     * 適応スキップ（優先1: AutoLearn成熟後）は温存する。
     */
    fun setForceFullCorrectionOnce() {
        forceFullCorrectionOnce = true
        Log.d(TAG, "[v1.1.41] forceFullCorrectionOnce set: next page will bypass CleanPage gates")
    }

    /**
     * v1.0.75: Phase 3検出結果からLLM用ヒントを生成
     * v1.0.76: 送り仮名、促音・長音、漢字字形の検出結果も含める
     * v1.0.77: ヒント表現を簡潔化（トークン削減）
     */
    private fun buildPhase3Hints(
        particleResult: ParticleMissingDetector.ParticleCorrectionResult?,
        okuriganaResult: OkuriganaCorrector.OkuriganaResult?,
        choonResult: SokuonChoonCorrector.ChoonResult?,
        kanjiResult: KanjiShapeCorrector.KanjiShapeResult?
    ): String? {
        val hints = mutableListOf<String>()

        // 助詞脱落ヒント（簡潔化: "A[助詞]B" 形式）
        // v1.0.81: 信頼度閾値を0.6→0.5に引き下げ（助詞ヒント生成率向上）
        if (particleResult != null && particleResult.suggestions.isNotEmpty()) {
            particleResult.suggestions.take(3).forEach { suggestion ->
                if (suggestion.confidence >= 0.5) {
                    hints.add("${suggestion.afterWord}[${suggestion.suggestedParticle}]${suggestion.beforeWord}")
                }
            }
        }

        // 送り仮名補正ヒント（簡潔化: "A→B" 形式）
        // v1.0.81: 信頼度閾値を0.6→0.5に引き下げ
        if (okuriganaResult != null && okuriganaResult.suggestions.isNotEmpty()) {
            okuriganaResult.suggestions.take(2).forEach { suggestion ->
                if (suggestion.confidence >= 0.5) {
                    hints.add("${suggestion.verbPattern}→${suggestion.verbStem}${suggestion.correctedForm}")
                }
            }
        }

        // 促音・長音補正ヒント（簡潔化: "A→B" 形式）
        // v1.0.81: 信頼度閾値を0.6→0.5に引き下げ
        if (choonResult != null && choonResult.suggestions.isNotEmpty()) {
            choonResult.suggestions.take(2).forEach { suggestion ->
                if (suggestion.confidence >= 0.5) {
                    hints.add("${suggestion.originalForm}→${suggestion.correctedForm}")
                }
            }
        }

        // 漢字字形補正ヒント（簡潔化: "A→B" 形式）
        // v1.0.81: 信頼度閾値を0.6→0.5に引き下げ
        if (kanjiResult != null && kanjiResult.suggestions.isNotEmpty()) {
            kanjiResult.suggestions.take(2).forEach { suggestion ->
                if (suggestion.confidence >= 0.5) {
                    hints.add("${suggestion.misrecognizedKanji}→${suggestion.correctKanji}")
                }
            }
        }

        // v1.0.78: Phase 3ヒント生成統計ログ
        // v1.0.81: 信頼度閾値を0.6→0.5に変更
        if (hints.isNotEmpty()) {
            var particleCount = 0
            var okuriganaCount = 0
            var choonCount = 0
            var kanjiCount = 0

            if (particleResult != null && particleResult.suggestions.isNotEmpty()) {
                particleCount = particleResult.suggestions.take(3).count { it.confidence >= 0.5 }
            }
            if (okuriganaResult != null && okuriganaResult.suggestions.isNotEmpty()) {
                okuriganaCount = okuriganaResult.suggestions.take(2).count { it.confidence >= 0.5 }
            }
            if (choonResult != null && choonResult.suggestions.isNotEmpty()) {
                choonCount = choonResult.suggestions.take(2).count { it.confidence >= 0.5 }
            }
            if (kanjiResult != null && kanjiResult.suggestions.isNotEmpty()) {
                kanjiCount = kanjiResult.suggestions.take(2).count { it.confidence >= 0.5 }
            }

            Log.d(TAG, "[v1.0.78 Phase3Hints] Generated ${hints.size} hints: particle=$particleCount, okurigana=$okuriganaCount, choon=$choonCount, kanji=$kanjiCount")
            return hints.joinToString(", ")
        } else {
            return null
        }
    }

    /**
     * メイン補正メソッド（文字列のみ）
     * OCR認識テキストを補正して返す
     * v1.0.31: Phase 1一般化パターンを優先適用
     * v1.0.32: Phase 2形態素解析を追加
     */
    fun correctText(originalText: String): String {
        if (originalText.isEmpty()) {
            return originalText
        }

        var correctedText = originalText

        // ステップ0: Phase 1 - 一般化パターンの適用（優先）
        correctedText = applyGeneralizedPatterns(correctedText)

        // ステップ1: 経済用語の単純置換（Phase 1で補完されなかったもの）
        correctedText = applyEconomicTermsCorrection(correctedText)

        // ステップ2: カタカナパターンの置換
        correctedText = applyKatakanaCorrection(correctedText)

        // ステップ3: 文脈依存の補正
        correctedText = applyContextualCorrection(correctedText)

        // ステップ4: Phase 2 - 形態素解析による未知語補正
        correctedText = applyMorphologicalAnalysis(correctedText)

        // 変更があった場合のみログ出力
        if (correctedText != originalText) {
            logCorrections(originalText, correctedText)
        }

        return correctedText
    }

    /**
     * メイン補正メソッド（OCR結果オブジェクト付き）
     * v1.0.33: Phase 3信頼度ベース補正を追加
     * v1.0.39: 信頼度スコアリング + LLM統合 + バリデーション
     * v1.1.33: previousContext追加（前ページのLLM補正済みテキストをLLMに渡す）
     */
    fun correctText(originalText: String, ocrResult: Text?, previousContext: String? = null): String {
        if (originalText.isEmpty()) {
            return originalText
        }

        var correctedText = originalText

        // v1.1.36 ステップ-0.5: 自動学習パターン適用（信頼度スコアリング付き）
        var autoLearnResult: AutoLearnManager.ApplyResult? = null
        val preAutoLearnText = correctedText  // v1.1.36: LLM確認用に保持
        if (ENABLE_AUTO_LEARN) {
            autoLearnResult = autoLearnManager.applyLearnedPatternsWithStats(correctedText)
            if (autoLearnResult.correctedText != correctedText) {
                Log.d(TAG, "[v1.1.36 AutoLearn] Applied ${autoLearnResult.appliedCount} patterns (avgConf=${String.format("%.2f", autoLearnResult.avgConfidence)}, highConf=${autoLearnResult.highConfidenceCount}, promoted=${autoLearnResult.totalPromoted})")
                correctedText = autoLearnResult.correctedText
            }
        }

        // v1.1.34 ステップ-0.3: 日本語テキスト正規化（不要スペース除去）
        val normalized = normalizeJapaneseSpaces(correctedText)
        if (normalized != correctedText) {
            val spaceCount = correctedText.length - normalized.length
            Log.d(TAG, "[v1.1.34 Normalize] Removed $spaceCount spurious spaces from Japanese text")
            correctedText = normalized
        }

        // v1.1.32 ステップ-1: 文字種異常検出補正（Stage 1 — 漢字↔カタカナの安全な補正）
        val anomalyCorrected = charTypeAnomalyCorrector.correct(correctedText)
        if (anomalyCorrected != correctedText) {
            Log.d(TAG, "[v1.1.32 Stage1] CharType anomaly corrections applied")
            correctedText = anomalyCorrected
        }

        // ステップ0: Phase 1 - 一般化パターンの適用（優先）
        correctedText = applyGeneralizedPatterns(correctedText)

        // ステップ1: 経済用語の単純置換（Phase 1で補完されなかったもの）
        correctedText = applyEconomicTermsCorrection(correctedText)

        // ステップ2: カタカナパターンの置換
        correctedText = applyKatakanaCorrection(correctedText)

        // ステップ3: 文脈依存の補正
        correctedText = applyContextualCorrection(correctedText)

        // ステップ4: Phase 2 - 形態素解析による未知語補正（v1.0.37で無効化）
        if (ENABLE_PHASE2) {
            correctedText = applyMorphologicalAnalysis(correctedText)
        }

        // ステップ5: Phase 3 - 信頼度ベース補正（OCR結果がある場合のみ）
        if (ocrResult != null) {
            correctedText = applyConfidenceBasedCorrection(correctedText, ocrResult)
        }

        // v1.0.64 ステップ5.5: Phase 3 UniDic - 助詞脱落検出と補完
        // v1.0.75: 検出結果を保存してLLMに渡す
        var phase3DetectionResult: ParticleMissingDetector.ParticleCorrectionResult? = null
        if (ENABLE_PARTICLE_DETECTION) {
            try {
                val detectionResult = particleDetector.detectAndSuggest(correctedText)
                if (detectionResult.suggestions.isNotEmpty()) {
                    Log.d(TAG, "[v1.0.64 Phase3 UniDic] Detected ${detectionResult.suggestions.size} particle missing patterns")

                    detectionResult.suggestions.forEach { suggestion ->
                        Log.d(TAG, "[v1.0.64 Phase3] ${suggestion.afterWord}【${suggestion.suggestedParticle}】${suggestion.beforeWord} (conf=${String.format("%.2f", suggestion.confidence)}, ${suggestion.reason})")
                    }

                    // v1.0.75: 検出結果を保存
                    phase3DetectionResult = detectionResult

                    // v1.1.30: LLM全文補正モードでは助詞挿入を直接適用しない
                    // 理由: OCR誤字に助詞を挿入すると悪化する（例: 龍要→龍の要、正しくは需要）
                    // 検出結果はLLMヒントとして渡されるため、LLMが適切に判断する
                    if (MIN_CONFIDENCE_FOR_PHASE1 < 1.0) {
                        val particleCorrected = particleDetector.applyCorrections(detectionResult, minConfidence = 0.40)
                        if (particleCorrected != correctedText) {
                            Log.d(TAG, "[v1.0.64 Phase3] Particle corrections applied")
                            correctedText = particleCorrected
                        }
                    } else {
                        Log.d(TAG, "[v1.1.30 Phase3] Particle corrections skipped (LLM mode), hints passed to LLM")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[v1.0.64 Phase3] Particle detection failed: ${e.message}", e)
            }
        }

        // v1.0.76 ステップ5.5.5: Phase 3 検出のみモード（LLMヒント用）
        // 送り仮名、促音・長音、漢字字形の検出を実行（補正は適用しない）
        var phase3OkuriganaResult: OkuriganaCorrector.OkuriganaResult? = null
        var phase3ChoonResult: SokuonChoonCorrector.ChoonResult? = null
        var phase3KanjiResult: KanjiShapeCorrector.KanjiShapeResult? = null

        // 送り仮名検出（フラグfalseでも検出だけは実行）
        if (!ENABLE_OKURIGANA_CORRECTION) {
            try {
                val okuriganaResult = okuriganaCorrector.detectAndCorrect(correctedText)
                if (okuriganaResult.suggestions.isNotEmpty()) {
                    Log.d(TAG, "[v1.0.76 Phase3 Okurigana Detection] Found ${okuriganaResult.suggestions.size} patterns (detection only)")
                    okuriganaResult.suggestions.take(3).forEach { suggestion ->
                        Log.d(TAG, "[v1.0.76 Okurigana] ${suggestion.verbPattern} → ${suggestion.verbStem}${suggestion.correctedForm} (conf=${String.format("%.2f", suggestion.confidence)})")
                    }
                    phase3OkuriganaResult = okuriganaResult
                }
            } catch (e: Exception) {
                Log.e(TAG, "[v1.0.76 Okurigana Detection] Failed: ${e.message}", e)
            }
        }

        // 促音・長音検出（フラグfalseでも検出だけは実行）
        if (!ENABLE_SOKUON_CHOON_CORRECTION) {
            try {
                val choonResult = sokuonChoonCorrector.detectAndCorrect(correctedText)
                if (choonResult.suggestions.isNotEmpty()) {
                    Log.d(TAG, "[v1.0.76 Phase3 SokuonChoon Detection] Found ${choonResult.suggestions.size} patterns (detection only)")
                    choonResult.suggestions.take(3).forEach { suggestion ->
                        Log.d(TAG, "[v1.0.76 SokuonChoon] '${suggestion.originalForm}' → '${suggestion.correctedForm}' (conf=${String.format("%.2f", suggestion.confidence)})")
                    }
                    phase3ChoonResult = choonResult
                }
            } catch (e: Exception) {
                Log.e(TAG, "[v1.0.76 SokuonChoon Detection] Failed: ${e.message}", e)
            }
        }

        // 漢字字形検出（フラグfalseでも検出だけは実行）
        if (!ENABLE_KANJI_SHAPE_CORRECTION) {
            try {
                val kanjiResult = kanjiShapeCorrector.detectAndCorrect(correctedText)
                if (kanjiResult.suggestions.isNotEmpty()) {
                    Log.d(TAG, "[v1.0.76 Phase3 KanjiShape Detection] Found ${kanjiResult.suggestions.size} patterns (detection only)")
                    kanjiResult.suggestions.take(3).forEach { suggestion ->
                        Log.d(TAG, "[v1.0.76 KanjiShape] '${suggestion.originalForm}' → '${suggestion.correctedForm}' (${suggestion.misrecognizedKanji}→${suggestion.correctKanji}, conf=${String.format("%.2f", suggestion.confidence)})")
                    }
                    phase3KanjiResult = kanjiResult
                }
            } catch (e: Exception) {
                Log.e(TAG, "[v1.0.76 KanjiShape Detection] Failed: ${e.message}", e)
            }
        }

        // v1.0.65 ステップ5.6: Phase 3 UniDic - 送り仮名補正
        if (ENABLE_OKURIGANA_CORRECTION) {
            try {
                val okuriganaResult = okuriganaCorrector.detectAndCorrect(correctedText)
                if (okuriganaResult.suggestions.isNotEmpty()) {
                    Log.d(TAG, "[v1.0.65 Okurigana] Found ${okuriganaResult.suggestions.size} okurigana patterns")

                    okuriganaResult.suggestions.forEach { suggestion ->
                        Log.d(TAG, "[v1.0.65 Okurigana] ${suggestion.verbPattern} → ${suggestion.verbStem}${suggestion.correctedForm} (conf=${String.format("%.2f", suggestion.confidence)}, ${suggestion.reason})")
                    }

                    val okCorrected = okuriganaCorrector.applyCorrections(okuriganaResult, minConfidence = 0.6)
                    if (okCorrected != correctedText) {
                        Log.d(TAG, "[v1.0.65 Okurigana] Corrections applied")
                        correctedText = okCorrected
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[v1.0.65 Okurigana] Failed: ${e.message}", e)
            }
        }

        // v1.0.66 ステップ5.7: Phase 3 - 促音・長音補正
        if (ENABLE_SOKUON_CHOON_CORRECTION) {
            try {
                val choonResult = sokuonChoonCorrector.detectAndCorrect(correctedText)
                if (choonResult.suggestions.isNotEmpty()) {
                    Log.d(TAG, "[v1.0.67 SokuonChoon] Found ${choonResult.suggestions.size} choon patterns")

                    choonResult.suggestions.forEach { suggestion ->
                        Log.d(TAG, "[v1.0.67 SokuonChoon] '${suggestion.originalForm}' → '${suggestion.correctedForm}' (conf=${String.format("%.2f", suggestion.confidence)}, ${suggestion.reason})")
                    }

                    val scCorrected = sokuonChoonCorrector.applyCorrections(choonResult, minConfidence = 0.65)
                    if (scCorrected != correctedText) {
                        Log.d(TAG, "[v1.0.67 SokuonChoon] Corrections applied")
                        correctedText = scCorrected
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[v1.0.67 SokuonChoon] Failed: ${e.message}", e)
            }
        }

        // v1.0.68 ステップ5.8: Phase 3 - 漢字字形類似誤認識補正
        if (ENABLE_KANJI_SHAPE_CORRECTION) {
            try {
                val kanjiResult = kanjiShapeCorrector.detectAndCorrect(correctedText)
                if (kanjiResult.suggestions.isNotEmpty()) {
                    Log.d(TAG, "[v1.0.68 KanjiShape] Found ${kanjiResult.suggestions.size} kanji shape patterns")

                    kanjiResult.suggestions.forEach { suggestion ->
                        Log.d(TAG, "[v1.0.68 KanjiShape] '${suggestion.originalForm}' → '${suggestion.correctedForm}' (${suggestion.misrecognizedKanji}→${suggestion.correctKanji}) conf=${String.format("%.2f", suggestion.confidence)}, ${suggestion.reason})")
                    }

                    val ksCorrected = kanjiShapeCorrector.applyCorrections(kanjiResult, minConfidence = 0.60)
                    if (ksCorrected != correctedText) {
                        Log.d(TAG, "[v1.0.68 KanjiShape] Corrections applied")
                        correctedText = ksCorrected
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[v1.0.68 KanjiShape] Failed: ${e.message}", e)
            }
        }

        // v1.0.39 ステップ6: Phase 1信頼度計算
        val phase1Confidence = calculatePhase1Confidence(originalText, correctedText)
        Log.d(TAG, "[v1.0.39] Phase 1 confidence: $phase1Confidence (patterns: ${appliedPatterns.size})")

        // v1.0.39 ステップ7: バリデーション（誤補正防止）
        if (ENABLE_VALIDATION && correctedText != originalText) {
            val validationResult = correctionValidator.validate(originalText, correctedText)
            if (!validationResult.valid) {
                Log.w(TAG, "[v1.0.39] Validation failed: ${validationResult.reason}, reverting to original")
                correctedText = originalText
            } else {
                Log.d(TAG, "[v1.0.39] Validation passed (confidence: ${validationResult.confidence})")
            }
        }

        // v1.0.39 ステップ8: LLM補正
        // v1.1.36: 適応パイプライン - AutoLearnの信頼度が高ければLLMをスキップ
        // v1.1.39: スマートLLMゲート - Phase3シグナルベースのクリーンページ検出
        //   ShapeSimilarHintsはゲートから除外（カタカナが存在するだけで発火するため）

        // Phase3の異常検出シグナルを集約（nilは「発火なし」）
        val phase3HasSignals = (phase3DetectionResult?.suggestions?.isNotEmpty() == true) ||
                               (phase3OkuriganaResult?.suggestions?.isNotEmpty() == true) ||
                               (phase3ChoonResult?.suggestions?.isNotEmpty() == true) ||
                               (phase3KanjiResult?.suggestions?.isNotEmpty() == true)

        // v1.1.41: ユーザー修正フラグを読み出してリセット（1ページ限定）
        val isForced = forceFullCorrectionOnce.also { forceFullCorrectionOnce = false }
        if (isForced) {
            Log.d(TAG, "[v1.1.41] Force full correction: CleanPage gates bypassed due to user correction on previous page")
        }

        var llmSkipped = false
        if (ENABLE_LLM_CORRECTION && phase1Confidence < MIN_CONFIDENCE_FOR_PHASE1) {
            val skipRec = autoLearnResult?.let { autoLearnManager.getSkipRecommendation(it) }
            when {
                // 優先1: 適応スキップ（AutoLearnパターンが成熟 ≥10個）— force時も有効（AutoLearnが成熟なら信頼できる）
                skipRec?.shouldSkip == true -> {
                    Log.d(TAG, "[v1.1.36 Adaptive] ★ LLM SKIPPED: ${skipRec.reason}")
                    llmSkipped = true
                }
                // 優先2: v1.1.39 クリーンページ — Phase3異常なし & AutoLearnが高信頼度で補正済み
                // v1.1.41: isForced=trueのときは適用しない（ユーザー修正後は念のためLLMを走らせる）
                !isForced && !phase3HasSignals && (autoLearnResult?.appliedCount ?: 0) > 0 -> {
                    Log.d(TAG, "[v1.1.39 CleanPage] ★ LLM SKIPPED: no Phase3 signals, AutoLearn applied ${autoLearnResult?.appliedCount} patterns (avgConf=${String.format("%.2f", autoLearnResult?.avgConfidence ?: 0f)})")
                    llmSkipped = true
                }
                // 優先3: v1.1.39 クリーンページ — Phase3異常なし & Phase1が多数パターン適用
                // v1.1.41: isForced=trueのときは適用しない
                !isForced && !phase3HasSignals && phase1Confidence >= 0.8 -> {
                    Log.d(TAG, "[v1.1.39 CleanPage] ★ LLM SKIPPED: no Phase3 signals, Phase1 high confidence ($phase1Confidence)")
                    llmSkipped = true
                }
                else -> {
                    val reason = skipRec?.reason ?: "AutoLearn not available"
                    Log.d(TAG, "[v1.1.39] LLM required: phase3Signals=$phase3HasSignals, phase1Conf=$phase1Confidence, isForced=$isForced, $reason")
                }
            }
        }
        // v1.1.41: LLM使用有無を記録（OverlayServiceがステータス表示に使用）
        lastCorrectionUsedLLM = !llmSkipped

        if (ENABLE_LLM_CORRECTION && phase1Confidence < MIN_CONFIDENCE_FOR_PHASE1 && !llmSkipped) {
            Log.d(TAG, "[v1.0.39] Phase 1 confidence low ($phase1Confidence), trying LLM correction")

            // v1.0.75: Phase 3ヒントを生成
            // v1.0.76: すべてのPhase 3検出結果を含める
            val phase3Hints = buildPhase3Hints(
                phase3DetectionResult,
                phase3OkuriganaResult,
                phase3ChoonResult,
                phase3KanjiResult
            )

            // v1.1.32: 形状類似ヒントを生成（Stage 2）
            val shapeSimilarHints = shapeSimilarHintBuilder.buildHints(correctedText)

            // Phase 3ヒントと形状類似ヒントを結合
            val combinedHints = listOfNotNull(shapeSimilarHints, phase3Hints)
                .joinToString(" | ")
                .ifEmpty { null }

            // v1.1.33: 前ページコンテキストをLLMに渡す（200文字制限）
            val llmContext = previousContext?.takeLast(200)
            if (llmContext != null) {
                Log.d(TAG, "[v1.1.33] Previous page context: ${llmContext.length} chars")
            }

            val preLLMText = correctedText  // v1.1.33: 自動学習用にLLM適用前を保持

            val (llmCorrected, llmConfidence) = llmCorrector.correctWithLLM(
                text = correctedText,
                context = llmContext,
                phase1Confidence = phase1Confidence,
                phase3Hints = combinedHints
            )

            // v1.1.30: 固定閾値で判定（Phase 1 confidence比較を廃止）
            // 旧ロジック: llmConfidence > phase1Confidence → Phase1が多くのパターンを適用すると
            // phase1Confidence=1.0になり、LLMのconfidence(常に<1.0)が常にrejectされていた
            val LLM_ACCEPTANCE_THRESHOLD = 0.5
            if (llmConfidence >= LLM_ACCEPTANCE_THRESHOLD) {
                Log.d(TAG, "[v1.1.30] LLM correction accepted (confidence: $llmConfidence, threshold: $LLM_ACCEPTANCE_THRESHOLD)")
                correctedText = llmCorrected

                // v1.1.33: LLM補正が受理された場合、差分を自動学習
                if (ENABLE_AUTO_LEARN && preLLMText != llmCorrected) {
                    try {
                        autoLearnManager.learnFromDiff(preLLMText, llmCorrected)
                        // v1.1.36: AutoLearnパターンの信頼度をLLM結果で確認・更新
                        autoLearnManager.confirmWithLLM(preAutoLearnText, autoLearnResult?.correctedText ?: preAutoLearnText, llmCorrected)
                        Log.d(TAG, "[v1.1.36 AutoLearn] ${autoLearnManager.getStats()}")
                    } catch (e: Exception) {
                        Log.e(TAG, "[v1.1.33 AutoLearn] Learning failed: ${e.message}", e)
                    }
                }
            } else {
                Log.d(TAG, "[v1.1.30] LLM correction rejected (confidence: $llmConfidence < threshold: $LLM_ACCEPTANCE_THRESHOLD)")
            }
        }

        // 変更があった場合のみログ出力
        if (correctedText != originalText) {
            logCorrections(originalText, correctedText)
        }

        return correctedText
    }

    /**
     * Phase 1: 一般化パターンの補正を適用
     * v1.0.31で追加
     * v1.0.39: パターン追跡機能追加
     */
    private fun applyGeneralizedPatterns(text: String): String {
        var result = text
        appliedPatterns.clear()  // v1.0.39: 追跡リストをクリア

        generalizedPatterns.forEach { rule ->
            val matches = rule.pattern.findAll(result).toList()
            if (matches.isNotEmpty()) {
                result = rule.pattern.replace(result, rule.correct)
                val patternInfo = "generalized:${rule.description}(${matches.size}件)"
                appliedPatterns.add(patternInfo)  // v1.0.39: パターンを記録
            }
        }

        if (appliedPatterns.isNotEmpty()) {
            Log.d(TAG, "[Phase1] Generalized patterns applied: ${appliedPatterns.joinToString(", ")}")
        }

        return result
    }

    /**
     * Phase 2: 文脈ベース形態素解析補正を適用
     * v1.0.34で再設計
     * v1.0.37で無効化（ENABLE_PHASE2 = false）
     *
     * 無効化理由:
     * - Kuromojiが誤認識テキストを既知単語の組み合わせとして処理
     * - 例: "能要" → "能"(名詞) + "要"(動詞) として認識
     * - 未知語として検出されないため、Phase 2のロジックが機能しない
     * - 実測補正率: 0% (v1.0.35, v1.0.36)
     * - Phase 1のみで十分な精度（57-85%）を達成可能
     */
    private fun applyMorphologicalAnalysis(text: String): String {
        try {
            val (correctedText, corrections) = morphAnalyzer.applyContextualCorrection(text)

            if (corrections.isNotEmpty()) {
                Log.d(TAG, "[Phase2] Contextual corrections applied: ${corrections.joinToString(", ")}")
            }

            return correctedText

        } catch (e: Exception) {
            Log.e(TAG, "[Phase2] Contextual analysis failed: ${e.message}", e)
            return text
        }
    }

    /**
     * Phase 3: 信頼度ベース補正を適用
     * v1.0.34で無効化（ML Kit Element構造により実現不可能）
     */
    private fun applyConfidenceBasedCorrection(text: String, ocrResult: Text): String {
        // Phase 3は無効化
        // 理由: ML Kit ElementsがsentenceレベルでグループNGされているため、
        // 単語レベルの補正が不可能
        return text
    }

    /**
     * 経済用語の補正を適用
     */
    private fun applyEconomicTermsCorrection(text: String): String {
        var result = text
        economicTermsDict.forEach { (wrong, correct) ->
            if (result.contains(wrong)) {
                result = result.replace(wrong, correct)
            }
        }
        return result
    }

    /**
     * カタカナの補正を適用
     */
    private fun applyKatakanaCorrection(text: String): String {
        var result = text
        katakanaPatterns.forEach { (wrong, correct) ->
            if (result.contains(wrong)) {
                result = result.replace(wrong, correct)
            }
        }
        return result
    }

    /**
     * 文脈依存の補正を適用
     * v1.0.39: パターン追跡機能追加
     */
    private fun applyContextualCorrection(text: String): String {
        var result = text
        var contextualCount = 0

        contextualPatterns.forEach { (pattern, replacement) ->
            val matches = pattern.findAll(result).toList()
            if (matches.isNotEmpty()) {
                result = pattern.replace(result, replacement)
                contextualCount += matches.size
            }
        }

        if (contextualCount > 0) {
            appliedPatterns.add("contextual:文脈補正(${contextualCount}件)")  // v1.0.39: パターンを記録
            Log.d(TAG, "[Phase1] Contextual patterns applied: $contextualCount corrections")
        }

        return result
    }

    /**
     * v1.0.39: Phase 1の信頼度を計算
     *
     * 基準:
     * - 変更なし: 0.3（OCRエラーがある可能性）
     * - 多数のパターンマッチ: 信頼度高
     * - 文脈パターンマッチ: 信頼度高
     * - 辞書のみのマッチ: 信頼度中
     *
     * @param original 元のテキスト
     * @param corrected 補正後のテキスト
     * @return 信頼度スコア（0.0-1.0）
     */
    private fun calculatePhase1Confidence(original: String, corrected: String): Double {
        if (original == corrected) {
            return 0.3  // 補正なし = 低信頼度（OCRエラーがあるかもしれない）
        }

        var confidence = 0.5  // ベース信頼度

        // 適用されたパターン数に応じて信頼度を上げる
        confidence += minOf(appliedPatterns.size * 0.1, 0.3)

        // 文脈パターンが適用された場合は信頼度を上げる
        if (appliedPatterns.any { it.contains("contextual") }) {
            confidence += 0.2
        }

        // 一般化パターンが多く適用された場合は信頼度を上げる
        val generalizedCount = appliedPatterns.count { it.contains("generalized") }
        if (generalizedCount >= 3) {
            confidence += 0.1
        }

        return confidence.coerceIn(0.0, 1.0)
    }

    /**
     * 補正ログを出力
     */
    private fun logCorrections(original: String, corrected: String) {
        // 変更箇所を特定
        val changes = findDifferences(original, corrected)

        Log.d(TAG, """
            |[TextCorrector] Applied corrections:
            |  Changes: ${changes.size}
            |  Original (${original.length} chars): ${original.take(50)}...
            |  Corrected (${corrected.length} chars): ${corrected.take(50)}...
            |  Details: ${changes.joinToString(", ")}
        """.trimMargin())
    }

    /**
     * テキストの差分を検出
     */
    private fun findDifferences(original: String, corrected: String): List<String> {
        val differences = mutableListOf<String>()

        // 経済用語の置換を検出
        economicTermsDict.forEach { (wrong, correct) ->
            if (original.contains(wrong) && corrected.contains(correct)) {
                differences.add("$wrong→$correct")
            }
        }

        // カタカナの置換を検出
        katakanaPatterns.forEach { (wrong, correct) ->
            if (original.contains(wrong) && corrected.contains(correct)) {
                differences.add("$wrong→$correct")
            }
        }

        return differences
    }

    /**
     * 補正統計情報を取得
     */
    fun getCorrectionStats(originalText: String, correctedText: String): CorrectionStats {
        val economicTermsCount = economicTermsDict.count { (wrong, _) ->
            originalText.contains(wrong)
        }

        val katakanaCount = katakanaPatterns.count { (wrong, _) ->
            originalText.contains(wrong)
        }

        val totalChanges = findDifferences(originalText, correctedText).size

        return CorrectionStats(
            economicTermsFixed = economicTermsCount,
            katakanaFixed = katakanaCount,
            totalCorrections = totalChanges,
            originalLength = originalText.length,
            correctedLength = correctedText.length
        )
    }

    /**
     * v1.1.5: 形状類似文字の優先補正
     *
     * OCR信頼度が低い場合（<0.7）に、形状が類似している文字の誤認識を優先的に補正
     * 分析結果: 人→入（69回）、本→木（31回）、日→目（12回）など
     *
     * @param text 補正対象テキスト
     * @param confidence OCR平均信頼度（0.0-1.0）
     * @return 補正後のテキスト
     */
    private fun correctShapeSimilarChars(text: String, confidence: Float): String {
        // 信頼度が0.7以上の場合は補正をスキップ
        if (confidence >= 0.7f) {
            return text
        }

        var corrected = text
        var correctionCount = 0

        // v1.1.5: 形状類似文字ペア（実測データに基づく優先順位）
        val shapeSimilarPairs = mapOf(
            "入" to "人",  // 最頻出（69回）
            "木" to "本",  // 2位（31回）
            "目" to "日",  // 3位（12回）
            "詰" to "話",  // 4位（11回）
            "刀" to "力",  // その他
            "未" to "末",
            "夫" to "大",
            "王" to "玉",
            "土" to "士",
            "戸" to "戸",
            "白" to "百"
        )

        shapeSimilarPairs.forEach { (wrong, correct) ->
            if (corrected.contains(wrong)) {
                val beforeCorrection = corrected
                corrected = corrected.replace(wrong, correct)

                if (corrected != beforeCorrection) {
                    correctionCount++
                    Log.d(TAG, "[v1.1.5 ShapeSimilar] $wrong → $correct (confidence=${"%.2f".format(confidence)})")
                }
            }
        }

        if (correctionCount > 0) {
            Log.d(TAG, "[v1.1.5 ShapeSimilar] Total corrections: $correctionCount")
        }

        return corrected
    }

    /**
     * v1.1.5: OCR結果から平均信頼度を計算
     *
     * @param ocrResult ML Kit OCR結果
     * @return 平均信頼度（0.0-1.0）、計算できない場合は1.0
     */
    private fun calculateAverageConfidence(ocrResult: Text): Float {
        val confidences = mutableListOf<Float>()

        ocrResult.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                line.elements.forEach { element ->
                    element.confidence?.let { conf ->
                        confidences.add(conf)
                    }
                }
            }
        }

        return if (confidences.isNotEmpty()) {
            confidences.average().toFloat()
        } else {
            1.0f  // 信頼度情報がない場合はデフォルト値
        }
    }

    /**
     * 補正統計情報データクラス
     */
    data class CorrectionStats(
        val economicTermsFixed: Int,
        val katakanaFixed: Int,
        val totalCorrections: Int,
        val originalLength: Int,
        val correctedLength: Int
    )

    /**
     * v1.1.34: 日本語テキスト中の不要スペースを除去
     *
     * OCRが日本語の縦書きテキストをスキャンする際、文字間に不要な半角/全角スペースが
     * 挿入されることがある（例: 「無制限 に」「できな い」）。
     * 日本語文字（ひらがな・カタカナ・漢字・句読点）同士の間のスペースは常にOCRエラー。
     *
     * 安全ルール:
     * - 日本語文字↔日本語文字の間のスペースのみ除去
     * - 日本語↔ASCII、ASCII↔ASCIIの間のスペースは保持
     * - 改行（\n）は保持
     */
    private fun normalizeJapaneseSpaces(text: String): String {
        // 日本語文字クラス: ひらがな + カタカナ + 漢字 + CJK句読点 + 長音符 + 繰り返し記号
        // \u3000 = 全角スペース（除去対象）
        // パターン: (日本語文字)(スペース+)(日本語文字) → $1$3
        val japaneseCharClass = "[\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF\\u3001-\\u3003\\u3005\\u3008-\\u3011\\u3014-\\u301F\\uFF01-\\uFF60]"
        val pattern = Regex("($japaneseCharClass)[\\s\\u3000]+($japaneseCharClass)")

        var result = text
        // 連続適用（「A B C」→ まず「AB C」、次に「ABC」）
        var prev: String
        do {
            prev = result
            result = pattern.replace(result) { match ->
                match.groupValues[1] + match.groupValues[2]
            }
        } while (result != prev)

        return result
    }
}
