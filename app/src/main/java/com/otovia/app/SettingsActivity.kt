package com.otovia.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.otovia.app.privacy.LocalCorrectionManager
import com.otovia.app.privacy.PrivacyPolicyActivity
import com.otovia.app.privacy.PrivacyPreferences
import com.otovia.app.AboutActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 設定画面
 *
 * v1.1.0で追加: 学習機能の設定を管理する画面
 *
 * 機能:
 * - 学習機能の有効/無効切り替え
 * - 学習パターン数と最終更新日時の表示
 * - プライバシーポリシーの表示
 * - 学習データの削除
 *
 * @since v1.1.0
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // アクションバーに戻るボタンを表示
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "設定"

        // PreferenceFragmentを表示
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * 設定フラグメント
     */
    class SettingsFragment : PreferenceFragmentCompat() {

        private val TAG = "Otovia_Settings"
        private lateinit var localCorrectionManager: LocalCorrectionManager

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            localCorrectionManager = LocalCorrectionManager.getInstance(requireContext())

            // 学習機能の有効/無効
            val learningEnabledPref = findPreference<SwitchPreferenceCompat>("learning_enabled")
            learningEnabledPref?.apply {
                isChecked = PrivacyPreferences.isLearningEnabled(requireContext())
                setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    PrivacyPreferences.setLearningEnabled(requireContext(), enabled)
                    Log.d(TAG, "Learning feature enabled: $enabled")
                    updateStatistics()
                    true
                }
            }

            // 学習パターン数表示
            updateStatistics()

            // プライバシーポリシーを見る
            findPreference<Preference>("view_privacy_policy")?.apply {
                setOnPreferenceClickListener {
                    val intent = Intent(requireContext(), PrivacyPolicyActivity::class.java)
                    startActivity(intent)
                    true
                }
            }

            // 学習データを削除
            findPreference<Preference>("delete_learning_data")?.apply {
                setOnPreferenceClickListener {
                    showDeleteConfirmationDialog()
                    true
                }
            }

            // アプリについて
            findPreference<Preference>("about")?.apply {
                setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(), AboutActivity::class.java))
                    true
                }
            }
        }

        override fun onResume() {
            super.onResume()
            // 画面に戻ったときに統計情報を更新
            updateStatistics()
        }

        /**
         * 統計情報を更新
         */
        private fun updateStatistics() {
            val patternCount = localCorrectionManager.getPatternCount()
            val lastUpdated = localCorrectionManager.getLastUpdated()

            // パターン数を更新
            findPreference<Preference>("learning_pattern_count")?.apply {
                summary = getString(R.string.settings_learning_pattern_count_summary, patternCount)
            }

            // 最終更新日時を更新
            findPreference<Preference>("learning_last_updated")?.apply {
                summary = if (lastUpdated > 0) {
                    val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                    getString(R.string.settings_learning_last_updated_summary, dateFormat.format(Date(lastUpdated)))
                } else {
                    getString(R.string.settings_learning_last_updated_summary, "未使用")
                }
            }

            Log.d(TAG, "Statistics updated: $patternCount patterns, last updated: $lastUpdated")
        }

        /**
         * 削除確認ダイアログを表示
         */
        private fun showDeleteConfirmationDialog() {
            val patternCount = localCorrectionManager.getPatternCount()

            AlertDialog.Builder(requireContext())
                .setTitle(R.string.delete_dialog_title)
                .setMessage(getString(R.string.delete_dialog_message, patternCount))
                .setPositiveButton(R.string.delete_dialog_confirm) { _, _ ->
                    deleteAllLearningData()
                }
                .setNegativeButton(R.string.delete_dialog_cancel, null)
                .show()
        }

        /**
         * すべての学習データを削除
         */
        private fun deleteAllLearningData() {
            val success = localCorrectionManager.clearAll()

            if (success) {
                Log.d(TAG, "All learning data deleted successfully")
                android.widget.Toast.makeText(
                    requireContext(),
                    R.string.delete_success_message,
                    android.widget.Toast.LENGTH_SHORT
                ).show()

                // 統計情報を更新
                updateStatistics()
            } else {
                Log.e(TAG, "Failed to delete learning data")
                android.widget.Toast.makeText(
                    requireContext(),
                    "削除に失敗しました",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
