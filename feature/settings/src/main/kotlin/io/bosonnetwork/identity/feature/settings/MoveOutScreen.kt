/*
 * Copyright (c) 2023 -      bosonnetwork.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.bosonnetwork.identity.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.PhonelinkSetup
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.identity.core.designsystem.component.SecureScreen
import io.bosonnetwork.identity.core.qr.rememberQrBitmap
import java.util.Locale

/**
 * Moving the identity to a new phone, on the old phone: a warning, the code on its own, then the QR code.
 * Screenshots and screen recording are blocked throughout. [onClose] leaves; [onRemove] leaves to remove
 * the identity from this phone, once it moved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveOutScreen(
    onClose: () -> Unit,
    onRemove: () -> Unit,
    viewModel: MoveOutViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SecureScreen()
    BackHandler(enabled = state.step == MoveOutStep.QR) { viewModel.backToCode() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.set_move)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.set_close))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (state.step) {
                    MoveOutStep.INTRO -> Intro(state, viewModel::start)
                    MoveOutStep.SEALING -> {
                        Spacer(Modifier.height(48.dp))
                        CircularProgressIndicator()
                    }
                    MoveOutStep.CODE -> Code(state.code.orEmpty(), viewModel::showQr)
                    MoveOutStep.QR -> Qr(state, viewModel::backToCode, viewModel::hide)
                    MoveOutStep.DONE -> Done(onRemove = onRemove, onKeep = onClose, onAgain = viewModel::start)
                }
            }
        }
    }
}

@Composable
private fun Intro(state: MoveOutUiState, onStart: () -> Unit) {
    Icon(Icons.Outlined.PhonelinkSetup, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
    Text(stringResource(R.string.set_move_intro_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Body(stringResource(R.string.set_move_intro_body))
    if (state.noNode) Body(stringResource(R.string.set_move_no_node))
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Text(
            stringResource(R.string.set_move_private),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.set_move_start)) }
}

@Composable
private fun Code(code: String, onNext: () -> Unit) {
    Text(stringResource(R.string.set_move_code_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Body(stringResource(R.string.set_move_code_body))
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Text(
            code,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 34.sp,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
    Body(stringResource(R.string.set_move_code_note))
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.set_move_code_next)) }
}

@Composable
private fun Qr(state: MoveOutUiState, onBack: () -> Unit, onDone: () -> Unit) {
    Text(stringResource(R.string.set_move_qr_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Body(stringResource(R.string.set_move_qr_body))
    // Dark modules on white, whatever the theme: scanners expect it. Large: the code holds a lot.
    Surface(color = Color.White, shape = RoundedCornerShape(16.dp)) {
        rememberQrBitmap(state.qr.orEmpty(), sizePx = 1080)?.let {
            Image(it, contentDescription = stringResource(R.string.set_move_qr_title), modifier = Modifier.padding(12.dp).size(300.dp))
        }
    }
    Text(
        stringResource(R.string.set_move_qr_hides, String.format(Locale.ROOT, "%d:%02d", state.remainingSeconds / 60, state.remainingSeconds % 60)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.set_move_qr_done)) }
    TextButton(onClick = onBack) { Text(stringResource(R.string.set_move_qr_show_code)) }
}

@Composable
private fun Done(onRemove: () -> Unit, onKeep: () -> Unit, onAgain: () -> Unit) {
    Text(stringResource(R.string.set_move_done_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Body(stringResource(R.string.set_move_done_body))
    Button(onClick = onRemove, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.set_move_done_remove)) }
    OutlinedButton(onClick = onKeep, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.set_move_done_keep)) }
    TextButton(onClick = onAgain) { Text(stringResource(R.string.set_move_done_again)) }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}
