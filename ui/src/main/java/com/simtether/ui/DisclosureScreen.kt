package com.simtether.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.simtether.shared.R

/**
 * Play's Prominent Disclosure requirement: before the system runtime
 * dialogs, the app must explain in-app what the sensitive permissions
 * are for and that data never leaves the user's own devices. Shown
 * once on first launch; declining just closes the app.
 */
@Composable
fun DisclosureScreen(
    @StringRes bodyRes: Int,
    onAgree: () -> Unit,
    onDecline: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("SimTether", style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.disclosure_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 24.dp),
        )
        Button(onClick = onAgree, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.disclosure_agree))
        }
        TextButton(onClick = onDecline, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.disclosure_decline))
        }
    }
}
