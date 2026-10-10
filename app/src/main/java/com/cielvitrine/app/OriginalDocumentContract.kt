package com.cielvitrine.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

internal data class OriginalDocument(val name: String, val mime: String)

/** Receives metadata only after complete container verification. The picker may rename it. */
internal class OriginalDocumentContract : ActivityResultContract<OriginalDocument, Uri?>() {
    override fun createIntent(context: Context, input: OriginalDocument): Intent {
        require(input.name.isNotBlank() && input.name != "." && input.name != ".." &&
            input.name.none { it == '/' || it == '\\' || it.isISOControl() }) {
            "Имя в архиве нельзя использовать для нового файла"
        }
        require(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+").matches(input.mime)) {
            "Тип файла в архиве не подходит для сохранения"
        }
        return Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mime).putExtra(Intent.EXTRA_TITLE, input.name)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}
