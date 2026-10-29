package ch.threema.app.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import ch.threema.app.R
import java.io.File

object ShareUtil {
    /**
     * Opens the share dialog for a file. The file must be in a location that is exposed via file_paths.xml, such as the cache directory.
     */
    @JvmStatic
    fun shareFile(context: Context, file: File, fileName: String, type: String) {
        val fileUri = FileProviderUtil.getUriForFile(context, file, fileName)
        val shareIntent = Intent(Intent.ACTION_SEND)
            .setType(type)
            .putExtra(Intent.EXTRA_STREAM, fileUri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(createChooser(context, shareIntent), null)
    }

    private fun createChooser(context: Context, intent: Intent): Intent {
        val chooserIntent = Intent.createChooser(intent, context.getString(R.string.share_via))
        if (context !is Activity) {
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return chooserIntent
    }
}
