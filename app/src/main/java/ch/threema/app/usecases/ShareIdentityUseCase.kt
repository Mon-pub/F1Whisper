package ch.threema.app.usecases

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import ch.threema.app.BuildConfig
import ch.threema.app.R
import ch.threema.app.utils.ConfigUtils
import ch.threema.domain.types.IdentityString

class ShareIdentityUseCase(
    private val appContext: Context,
) {
    fun call(identity: IdentityString, name: String): Result =
        try {
            // On the onprem build, share just the bare 8-char Threema ID (no URL prefix) to avoid the
            // Android 12+ "open supported links" verification issue with the contact action URL.
            val text = if (ConfigUtils.isOnPremBuild()) {
                identity
            } else {
                "$name https://${BuildConfig.contactActionUrl}/$identity"
            }
            shareText(text)
            Result.Success
        } catch (_: ActivityNotFoundException) {
            Result.Error
        }

    private fun shareText(text: String) {
        val shareIntent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        appContext.startActivity(createChooser(shareIntent), null)
    }

    private fun createChooser(intent: Intent): Intent =
        Intent.createChooser(intent, appContext.getString(R.string.share_via))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    sealed interface Result {
        data object Success : Result
        data object Error : Result
    }
}
