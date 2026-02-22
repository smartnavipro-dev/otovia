package com.otovia.app.ocr

import android.util.Log
import com.atilika.kuromoji.unidic.Tokenizer

/**
 * v1.0.71: Phase 3共有UniDic Tokenizerシングルトン
 *
 * 問題: 各Phase 3 Correctorが独立したUniDic Tokenizerインスタンスを保持
 *   - ParticleDetector: ~40MB
 *   - OkuriganaCorrector: ~40MB
 *   - SokuonChoonCorrector: ~40MB
 *   - KanjiShapeCorrector: ~40MB
 *   合計: 160MB → OutOfMemoryError
 *
 * 解決策: 単一の共有Tokenizerインスタンスを全Correctorで使用
 *   合計: 40MB (75%削減)
 *
 * メモリ効率:
 *   - v1.0.69 (全有効): 160MB → OOM
 *   - v1.0.70 (ParticleDetectorのみ): 40MB
 *   - v1.0.71 (全有効 + 共有): 40MB ← 目標
 */
object Phase3SharedTokenizer {
    private const val TAG = "Otovia_Phase3Shared"

    /**
     * 共有UniDic Tokenizerインスタンス
     * lazy初期化により最初のアクセス時のみメモリ確保
     */
    val unidicTokenizer: Tokenizer by lazy {
        Log.d(TAG, "[v1.0.71] Initializing shared UniDic tokenizer for all Phase 3 correctors...")
        val startTime = System.currentTimeMillis()

        val tokenizer = Tokenizer()

        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "[v1.0.71] Shared UniDic tokenizer initialized successfully (${duration}ms, ~40MB)")
        Log.d(TAG, "[v1.0.71] This tokenizer will be shared across:")
        Log.d(TAG, "[v1.0.71]   - ParticleDetector (助詞脱落検出)")
        Log.d(TAG, "[v1.0.71]   - OkuriganaCorrector (送り仮名補正)")
        Log.d(TAG, "[v1.0.71]   - SokuonChoonCorrector (促音・長音補正)")
        Log.d(TAG, "[v1.0.71]   - KanjiShapeCorrector (漢字字形誤認識補正)")

        tokenizer
    }
}
