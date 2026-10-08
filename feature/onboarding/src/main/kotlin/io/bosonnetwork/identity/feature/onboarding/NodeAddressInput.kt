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

package io.bosonnetwork.identity.feature.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.bosonnetwork.Id
import io.bosonnetwork.director.client.SuperNodeCode
import io.bosonnetwork.identity.core.model.NodeAddress
import io.bosonnetwork.identity.core.qr.QrScanner

/**
 * What a node's QR code says: `boson:supernode:1:<nodeId>:<url>`, as the portal and Boson Identity show it,
 * or the JSON `{"url": ..., "id": ...}` they showed before, whose id may be missing (then empty here).
 */
internal data class ScannedNode(val url: String, val nodeId: String)

/** Reads a node's QR code; null when [text] is not one. */
internal fun scannedNode(text: String): ScannedNode? {
    val code = SuperNodeCode.parse(text).orElse(null) ?: return null
    return ScannedNode(code.url, code.nodeId.map { it.toBase58String() }.orElse(""))
}

/** A typed node address, checked: the node to ask, or what is wrong with it. */
internal sealed interface TypedNode {
    data class Valid(val node: NodeAddress) : TypedNode
    data class Invalid(@StringRes val message: Int) : TypedNode
}

/** Checks a typed [address] and the optional [nodeId] that pins the node's certificate. */
internal fun typedNode(address: String, nodeId: String): TypedNode {
    val node = try {
        NodeAddress.parse(address, nodeId.ifBlank { null })
    } catch (e: IllegalArgumentException) {
        return TypedNode.Invalid(R.string.onb_join_invalid_address)
    }
    val pinned = node.nodeId
    if (pinned != null && runCatching { Id.of(pinned) }.isFailure) return TypedNode.Invalid(R.string.onb_join_invalid_node_id)
    return TypedNode.Valid(node)
}

/**
 * Choosing a node: scanning its QR code (asking for the camera first), or typing its address, with
 * its node id under "Advanced options". [onScan] runs once the camera may be used; [onCheck] asks the
 * node typed.
 */
@Composable
internal fun NodeAddressFields(
    address: String,
    nodeId: String,
    busy: Boolean,
    onAddressChange: (String) -> Unit,
    onNodeIdChange: (String) -> Unit,
    onScan: () -> Unit,
    onCheck: () -> Unit,
) {
    val context = LocalContext.current
    var advanced by rememberSaveable { mutableStateOf(nodeId.isNotEmpty()) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onScan()
    }
    OutlinedButton(
        onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                onScan()
            } else {
                cameraPermission.launch(Manifest.permission.CAMERA)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.onb_join_scan)) }
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = address,
        onValueChange = onAddressChange,
        label = { Text(stringResource(R.string.onb_join_address_label)) },
        placeholder = { Text("node.example.com") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    if (advanced) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = nodeId,
            onValueChange = onNodeIdChange,
            label = { Text(stringResource(R.string.onb_join_node_id_label)) },
            supportingText = { Text(stringResource(R.string.onb_join_node_id_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        TextButton(onClick = { advanced = true }) { Text(stringResource(R.string.onb_join_advanced)) }
    }
    Spacer(Modifier.height(8.dp))
    if (busy) {
        CircularProgressIndicator()
    } else {
        Button(onClick = onCheck, enabled = address.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onb_join_check))
        }
    }
}

/** Scanning a node's QR code. */
@Composable
internal fun NodeScanStep(onScanned: (String) -> Unit, onCancel: () -> Unit) {
    StepTitle(stringResource(R.string.onb_join_scan_title))
    QrScanner(onScanned = onScanned, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
    TextButton(onClick = onCancel) { Text(stringResource(R.string.onb_cancel)) }
}
