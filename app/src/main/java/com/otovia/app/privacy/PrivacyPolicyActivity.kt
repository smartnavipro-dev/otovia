package com.otovia.app.privacy

import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.otovia.app.R
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * プライバシーポリシー表示画面
 *
 * v1.1.0で追加: プライバシーポリシーの全文を表示するActivity
 *
 * 機能:
 * - PRIVACY_POLICY.md（英語）またはPRIVACY_POLICY_ja.md（日本語）を表示
 * - ロケールに応じて自動的に言語を選択
 * - 「閉じる」ボタンで前の画面に戻る
 * - 「同意して閉じる」ボタン（同意ダイアログからの遷移時のみ表示）
 *
 * 起動方法:
 * ```kotlin
 * val intent = Intent(this, PrivacyPolicyActivity::class.java)
 * startActivity(intent)
 * ```
 *
 * @since v1.1.0
 */
class PrivacyPolicyActivity : AppCompatActivity() {

    private val TAG = "Otovia_PolicyActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // シンプルなレイアウトを動的に作成（後でXMLレイアウトに置き換え可能）
        val scrollView = ScrollView(this)
        val textView = TextView(this).apply {
            setPadding(48, 48, 48, 48)
            textSize = 14f
        }
        scrollView.addView(textView)
        setContentView(scrollView)

        // アクションバーの設定
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.privacy_policy_title)
        }

        // プライバシーポリシーを読み込んで表示
        loadPrivacyPolicy(textView)

        Log.d(TAG, "PrivacyPolicyActivity created")
    }

    /**
     * プライバシーポリシーを読み込んで表示
     *
     * @param textView 表示先のTextView
     */
    private fun loadPrivacyPolicy(textView: TextView) {
        try {
            // ロケールに応じてファイルを選択
            val locale = resources.configuration.locales[0]
            val fileName = if (locale.language == "ja") {
                "PRIVACY_POLICY_ja.md"
            } else {
                "PRIVACY_POLICY.md"
            }

            Log.d(TAG, "Loading privacy policy: $fileName (locale: ${locale.language})")

            // assetsフォルダからMarkdownファイルを読み込む
            val inputStream = assets.open(fileName)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val content = reader.use { it.readText() }

            // Markdownをプレーンテキストとして表示（後でMarkwonライブラリで整形）
            textView.text = content

            Log.d(TAG, "Privacy policy loaded successfully (${content.length} characters)")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load privacy policy", e)
            textView.text = getString(R.string.privacy_policy_title) + "\n\n" +
                    "Failed to load privacy policy. Please check the documentation at:\n" +
                    "https://smartnavipro-dev.github.io/otovia/privacy.html"
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                // 戻るボタンが押された
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
