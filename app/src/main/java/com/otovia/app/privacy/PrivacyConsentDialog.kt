package com.otovia.app.privacy

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.otovia.app.R

/**
 * プライバシー同意ダイアログ
 *
 * v1.1.0で追加: 初回起動時に学習機能への同意を取得するダイアログ
 *
 * 機能:
 * - 初回起動時のみ表示（shouldShowConsentDialog() == true の場合）
 * - 3つの選択肢を提供:
 *   1. 同意する → 学習機能が有効化される
 *   2. 同意しない → 学習機能が無効のまま
 *   3. ポリシーを見る → PrivacyPolicyActivity を起動
 * - Material Design 3対応
 *
 * 使用例:
 * ```kotlin
 * if (PrivacyPreferences.shouldShowConsentDialog(this)) {
 *     PrivacyConsentDialog.show(this) { consented ->
 *         if (consented) {
 *             // 学習機能を有効化
 *         }
 *     }
 * }
 * ```
 *
 * @since v1.1.0
 */
object PrivacyConsentDialog {

    private const val TAG = "Otovia_ConsentDialog"

    /**
     * プライバシー同意ダイアログを表示
     *
     * @param activity 呼び出し元のActivity
     * @param onResult 同意結果を受け取るコールバック（true: 同意, false: 拒否）
     * @return 表示されたダイアログのインスタンス
     */
    fun show(activity: Activity, onResult: (Boolean) -> Unit): AlertDialog {
        Log.d(TAG, "Showing privacy consent dialog")

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.privacy_consent_title))
            .setMessage(activity.getString(R.string.privacy_consent_message))
            .setCancelable(false) // 必ず選択を強制（初回起動時のみなので）
            .setPositiveButton(activity.getString(R.string.privacy_consent_agree)) { dialog, _ ->
                Log.d(TAG, "User agreed to privacy policy")
                PrivacyPreferences.saveConsent(activity, consent = true)
                onResult(true)
                dialog.dismiss()
            }
            .setNegativeButton(activity.getString(R.string.privacy_consent_disagree)) { dialog, _ ->
                Log.d(TAG, "User disagreed to privacy policy")
                PrivacyPreferences.saveConsent(activity, consent = false)
                onResult(false)
                dialog.dismiss()
            }
            .setNeutralButton(activity.getString(R.string.privacy_consent_view_policy)) { dialog, _ ->
                Log.d(TAG, "User requested to view privacy policy")
                // プライバシーポリシー画面を開く
                val intent = Intent(activity, PrivacyPolicyActivity::class.java)
                activity.startActivity(intent)
                // ダイアログは閉じないで待機（ユーザーが戻ってきて選択できるように）
                // （ポリシー画面から戻った後も同意を求める必要があるため）
            }
            .create()

        dialog.show()

        // アクセシビリティ対応
        dialog.window?.decorView?.contentDescription =
            activity.getString(R.string.privacy_consent_dialog_description)

        return dialog
    }

    /**
     * プライバシー同意ダイアログを表示（簡易版）
     *
     * コールバックなしで表示する場合に使用
     *
     * @param activity 呼び出し元のActivity
     * @return 表示されたダイアログのインスタンス
     */
    fun show(activity: Activity): AlertDialog {
        return show(activity) { consented ->
            Log.d(TAG, "User consent recorded: $consented")
        }
    }

    /**
     * 同意が必要かどうかをチェックして、必要なら自動的にダイアログを表示
     *
     * MainActivity の onCreate で呼び出すことを想定
     *
     * @param activity 呼び出し元のActivity
     * @param onResult 同意結果を受け取るコールバック（オプション）
     * @return ダイアログが表示された場合は AlertDialog、表示不要の場合は null
     */
    fun showIfNeeded(activity: Activity, onResult: ((Boolean) -> Unit)? = null): AlertDialog? {
        return if (PrivacyPreferences.shouldShowConsentDialog(activity)) {
            Log.d(TAG, "Consent dialog needs to be shown")
            if (onResult != null) {
                show(activity, onResult)
            } else {
                show(activity)
            }
        } else {
            Log.d(TAG, "Consent dialog already shown previously")
            null
        }
    }
}
