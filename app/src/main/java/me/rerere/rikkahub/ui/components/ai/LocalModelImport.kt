package me.rerere.rikkahub.ui.components.ai

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.locallm.LocalRuntime
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.pages.setting.locallm.SettingLocalLlmViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * One-tap import of a model file the user already has in device storage, shown inside the
 * chat model picker. A `.gguf` goes to the llama.cpp runtime and a `.litertlm` to LiteRT;
 * the view model copies the file into app storage, enables the provider and, when no chat
 * model is selected yet, selects the imported one.
 */
@Composable
fun LocalModelImportButton(modifier: Modifier = Modifier) {
    val llamaVm = koinViewModel<SettingLocalLlmViewModel>(
        key = "configure-${LocalRuntime.LlamaCpp.displayName}",
        parameters = { parametersOf(LocalRuntime.LlamaCpp) },
    )
    val liteRtVm = koinViewModel<SettingLocalLlmViewModel>(
        key = "configure-${LocalRuntime.LiteRT.displayName}",
        parameters = { parametersOf(LocalRuntime.LiteRT) },
    )
    val llamaProgress by llamaVm.downloadProgress.collectAsStateWithLifecycle()
    val liteRtProgress by liteRtVm.downloadProgress.collectAsStateWithLifecycle()
    val llamaError by llamaVm.errorMessage.collectAsStateWithLifecycle()
    val liteRtError by liteRtVm.errorMessage.collectAsStateWithLifecycle()
    var pickError by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val unsupported = stringResource(R.string.local_model_import_unsupported)
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pickError = null
        val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            } else null
        } ?: uri.lastPathSegment.orEmpty()
        val vm = when {
            fileName.endsWith(".gguf", ignoreCase = true) -> llamaVm
            fileName.endsWith(".litertlm", ignoreCase = true) -> liteRtVm
            else -> {
                pickError = unsupported
                return@rememberLauncherForActivityResult
            }
        }
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: return@rememberLauncherForActivityResult
        vm.installPickedFile(inputStream, fileName)
    }

    val progress = llamaProgress ?: liteRtProgress
    val error = pickError ?: llamaError ?: liteRtError

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedButton(
            onClick = { filePicker.launch(arrayOf("*/*")) },
            enabled = progress == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.local_model_import_action))
        }
        Text(
            text = stringResource(R.string.local_model_import_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        progress?.let { p ->
            if (p.totalBytes != null && p.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { p.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = stringResource(R.string.local_model_import_copying),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        error?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
