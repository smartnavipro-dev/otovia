#!/bin/bash

echo "=== v1.1.12 ログ分析レポート ==="
echo ""

echo "1. OCR統計:"
total_ocr=$(grep -c "Performing OCR" test_v1.1.12_full_log.txt)
ocr_success=$(grep -c "OCR success" test_v1.1.12_full_log.txt)
ocr_duplicate=$(grep -c "OCR duplicate" test_v1.1.12_full_log.txt)
ocr_failed=$(grep -c "OCR failed" test_v1.1.12_full_log.txt)
no_image=$(grep -c "No image available" test_v1.1.12_full_log.txt)

echo "  - OCR実行試行: $total_ocr回"
echo "  - OCR成功: $ocr_success回"
echo "  - OCR重複（同じテキスト）: $ocr_duplicate回"
echo "  - OCR失敗: $ocr_failed回"
echo "  - 画像なし: $no_image回"
echo ""

echo "2. ページターン:"
next_page=$(grep -c "Next page" test_v1.1.12_full_log.txt)
prev_page=$(grep -c "Previous page" test_v1.1.12_full_log.txt)
echo "  - 次ページ: $next_page回"
echo "  - 前ページ: $prev_page回"
echo ""

echo "3. TTS（読み上げ）:"
tts_started=$(grep -c "TTS started" test_v1.1.12_full_log.txt)
tts_completed=$(grep -c "TTS completed" test_v1.1.12_full_log.txt)
echo "  - TTS開始: $tts_started回"
echo "  - TTS完了: $tts_completed回"
echo ""

echo "4. Bitmap管理:"
bitmap_recycled=$(grep -c "Bitmap recycled" test_v1.1.12_full_log.txt)
echo "  - Bitmap解放: $bitmap_recycled回"
echo ""

echo "5. テキスト補正:"
phase1_corrections=$(grep -c "Generalized patterns applied" test_v1.1.12_full_log.txt)
phase3_detected=$(grep "Phase3\] Corrections applied" test_v1.1.12_full_log.txt | head -1 | grep -oP '\d+/\d+' | head -1)
echo "  - Phase1（一般パターン）: $phase1_corrections回"
echo "  - Phase3（助詞検出）: $phase3_detected"
echo ""

echo "6. パフォーマンス（処理時間）:"
grep "Bitmap conversion completed" test_v1.1.12_full_log.txt | grep -oP 'Total time: \K\d+' | awk '{sum+=$1; count++} END {print "  - 平均処理時間: " (count>0 ? sum/count : 0) "ms (" count "サンプル)"}'
echo ""

echo "7. OCR信頼度:"
grep "AvgConf:" test_v1.1.12_full_log.txt | grep -oP 'AvgConf: \K[\d.]+' | awk '{sum+=$1; count++} END {print "  - 平均信頼度: " (count>0 ? sum/count : 0) " (" count "サンプル)"}'
echo ""

