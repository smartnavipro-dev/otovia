package com.otovia.app.privacy

import android.content.Context
import android.content.SharedPreferences
import com.otovia.app.R

/**
 * プライバシー関連のSharedPreferences管理クラス
 *
 * v1.1.0で追加: ユーザーの学習機能への同意状態を管理
 *
 * 機能:
 * - 同意状態の保存・取得
 * - 同意日時の記録
 * - ダイアログ表示状態の管理
 *
 * @since v1.1.0
 */
object PrivacyPreferences {

    // SharedPreferences名
    private const val PREF_NAME = "privacy_prefs"

    // PreferenceKeys
    private const val KEY_LEARNING_CONSENT = "learning_consent_given"
    private const val KEY_CONSENT_DATE = "consent_date"
    private const val KEY_CONSENT_VERSION = "consent_policy_version"
    private const val KEY_CONSENT_DIALOG_SHOWN = "consent_dialog_shown"
    private const val KEY_LEARNING_ENABLED = "learning_enabled"

    // プライバシーポリシーバージョン
    const val CURRENT_POLICY_VERSION = "v1.0"

    /**
     * SharedPreferencesインスタンスを取得
     */
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * ユーザーが学習機能に同意しているか確認
     *
     * @param context アプリケーションコンテキスト
     * @return 同意している場合true
     */
    fun hasUserConsented(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_LEARNING_CONSENT, false)
    }

    /**
     * 同意ダイアログを表示すべきか判定
     *
     * @param context アプリケーションコンテキスト
     * @return 表示すべき場合true（初回起動時のみ）
     */
    fun shouldShowConsentDialog(context: Context): Boolean {
        val prefs = getPreferences(context)
        // ダイアログを一度も表示していない場合のみtrue
        return !prefs.getBoolean(KEY_CONSENT_DIALOG_SHOWN, false)
    }

    /**
     * ユーザーの同意状態を保存
     *
     * @param context アプリケーションコンテキスト
     * @param consent 同意した場合true、拒否した場合false
     */
    fun saveConsent(context: Context, consent: Boolean) {
        getPreferences(context).edit().apply {
            putBoolean(KEY_LEARNING_CONSENT, consent)
            putBoolean(KEY_LEARNING_ENABLED, consent)
            putLong(KEY_CONSENT_DATE, System.currentTimeMillis())
            putString(KEY_CONSENT_VERSION, CURRENT_POLICY_VERSION)
            putBoolean(KEY_CONSENT_DIALOG_SHOWN, true)
            apply()
        }
    }

    /**
     * 学習機能が有効かどうか取得
     *
     * @param context アプリケーションコンテキスト
     * @return 有効な場合true
     */
    fun isLearningEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_LEARNING_ENABLED, false)
    }

    /**
     * 学習機能の有効/無効を設定
     *
     * @param context アプリケーションコンテキスト
     * @param enabled 有効にする場合true
     */
    fun setLearningEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit().apply {
            putBoolean(KEY_LEARNING_ENABLED, enabled)
            apply()
        }
    }

    /**
     * 同意日時を取得
     *
     * @param context アプリケーションコンテキスト
     * @return 同意日時のタイムスタンプ（未同意の場合は0）
     */
    fun getConsentDate(context: Context): Long {
        return getPreferences(context).getLong(KEY_CONSENT_DATE, 0L)
    }

    /**
     * 同意したポリシーバージョンを取得
     *
     * @param context アプリケーションコンテキスト
     * @return ポリシーバージョン文字列（未同意の場合は空文字）
     */
    fun getConsentPolicyVersion(context: Context): String {
        return getPreferences(context).getString(KEY_CONSENT_VERSION, "") ?: ""
    }

    /**
     * すべてのプライバシー設定をクリア（デバッグ用）
     *
     * @param context アプリケーションコンテキスト
     */
    fun clearAll(context: Context) {
        getPreferences(context).edit().clear().apply()
    }

    /**
     * 同意ダイアログの表示フラグをリセット（再表示用）
     *
     * @param context アプリケーションコンテキスト
     */
    fun resetConsentDialogShown(context: Context) {
        getPreferences(context).edit().apply {
            putBoolean(KEY_CONSENT_DIALOG_SHOWN, false)
            apply()
        }
    }
}
