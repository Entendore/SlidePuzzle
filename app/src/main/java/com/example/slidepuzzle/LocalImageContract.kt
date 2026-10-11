package com.example.slidepuzzle

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * Gallery picker restricted to on-device files. EXTRA_LOCAL_ONLY tells the
 * picker to hide cloud-only items, so a picked image is readable offline.
 */
class LocalImageContract : ActivityResultContract<String, Uri?>() {

    override fun createIntent(context: Context, input: String): Intent =
        Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input)
            .putExtra(Intent.EXTRA_LOCAL_ONLY, true)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}
