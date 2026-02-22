# Otovia

<div align="center">

**電子書籍を、声の旅へ。**
*Turn any e-book into an audio journey.*

[![Android](https://img.shields.io/badge/Platform-Android%205.0%2B-green.svg)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org)
[![Version](https://img.shields.io/badge/Version-1.1.52-blue.svg)]()

</div>

---

## 概要

Otoviaは、画面に表示されたテキストをAIが自動でキャプチャ・補正・読み上げるAndroidアクセシビリティアプリです。

通勤中・家事中・運動中——目を使えない時間も、本は読める。

**アプリ名の由来**: 音（oto）+ via（道）。隠れた "auto"（自動）が入っています。

---

## 機能

| 機能 | 説明 |
|------|------|
| **どのアプリでも動く** | 画面にテキストが表示されていれば対応（Kindle / Kinoppy / BookWalker / honto / Chrome 等） |
| **AI誤字補正** | Gemini AIがOCR認識ミスをリアルタイムで補正 |
| **自動ページめくり** | 読み上げ完了時にスワイプジェスチャーで自動ページ遷移 |
| **先読みプリフェッチ** | 次ページを事前処理してページ間の沈黙をゼロに |
| **AutoLearn** | ユーザー修正から学習し、同じ誤字を繰り返さない |
| **フローティングコントローラー** | 画面を見ずに操作可能なオーバーレイUI |

---

## アーキテクチャ

```
画面キャプチャ → ML Kit OCR → AI補正（Gemini）→ TTS読み上げ → 自動ページめくり
                                     ↕
                              AutoLearn（端末内学習）
```

**主要コンポーネント:**
- `OverlayService` — 画面キャプチャ・OCR・TTS・プリフェッチのメインパイプライン
- `TextCorrector` — Phase1パターン補正 + Phase3形態素解析 + LLM補正
- `LLMCorrector` — Gemini 2.5 Flash API統合
- `AutoLearnManager` — ユーザー修正からの自動学習・パターン昇格
- `AutoPageTurnService` — アクセシビリティサービスによる自動ページめくり

---

## ビルド方法

### 必要条件

- JDK 17
- Android SDK 34
- Android Studio Hedgehog 以降（任意）

### セットアップ

1. リポジトリをクローン:
```bash
git clone https://github.com/smartnavipro-dev/otovia.git
cd otovia
```

2. `local.properties` を作成し以下を設定（このファイルは `.gitignore` 対象）:
```properties
sdk.dir=/path/to/Android/Sdk
GEMINI_API_KEY=your_gemini_api_key_here
SIGNING_STORE_PASSWORD=your_keystore_password
SIGNING_KEY_ALIAS=your_key_alias
SIGNING_KEY_PASSWORD=your_key_password
```

3. ビルド:
```bash
./gradlew bundleRelease   # AAB（Play Store用）
./gradlew assembleRelease # APK
```

### Gemini APIキーの取得

1. [Google AI Studio](https://aistudio.google.com/app/apikey) にアクセス
2. 「APIキーを作成」
3. `local.properties` の `GEMINI_API_KEY` に設定

---

## 権限

| 権限 | 用途 |
|------|------|
| 画面キャプチャ（MediaProjection） | OCRによるテキスト認識 |
| アクセシビリティサービス | 自動ページめくり（視覚的読書困難者の補助） |
| オーバーレイ表示（SYSTEM_ALERT_WINDOW） | フローティングコントローラー |
| フォアグラウンドサービス | 読み上げ中のサービス継続 |
| 通知（POST_NOTIFICATIONS） | 読み上げ中のコントロール表示 |
| インターネット | Gemini API（OCR補正）のみ |

---

## プライバシー

- 取得した画面情報はOCR処理後に即座に破棄
- 外部送信はGemini API（OCR補正目的のみ）
- 個人情報の収集なし・アカウント登録不要
- AutoLearnのパターンデータはAES256-GCMで暗号化して端末内に保存

詳細: [プライバシーポリシー](docs/privacy.html)

---

## OSSライセンス

本アプリが使用するオープンソースライブラリ:

| ライブラリ | ライセンス |
|-----------|-----------|
| [Kuromoji](https://github.com/atilika/kuromoji) | Apache 2.0 |
| [OpenCV](https://opencv.org/) | Apache 2.0 |
| [AndroidX](https://developer.android.com/jetpack/androidx) | Apache 2.0 |
| [Kotlinx Coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache 2.0 |
| [Gson](https://github.com/google/gson) | Apache 2.0 |
| [Google AI Generative AI SDK](https://github.com/google/generative-ai-android) | Apache 2.0 |
| IPAdic morphological dictionary | IPAdic License (modified BSD) |
| UniDic morphological dictionary | UniDic License (modified BSD) |
| [ML Kit Text Recognition](https://developers.google.com/ml-kit) | Google Terms of Service |

---

## お問い合わせ

- **GitHub Issues**: https://github.com/smartnavipro-dev/otovia/issues
- **Contact**: contact@smartnavipro.dev
