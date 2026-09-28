package com.simtether.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.simtether.shared.R

/**
 * Client-role subscription gate — full-screen, no dismissal: the
 * subscription IS the product. Price and trial length come from Play
 * so the label always matches what the store sheet will charge.
 */
@Composable
fun PaywallScreen(
    price: String?,
    trialDays: Int,
    onSubscribe: () -> Unit,
    onRestore: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("SimTether", style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.paywall_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            stringResource(R.string.paywall_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 24.dp),
        )
        // Price-less means the product hasn't loaded — subscribing
        // anyway would either no-op or launch a blind sheet.
        Button(
            onClick = onSubscribe,
            enabled = price != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    trialDays > 0 && price != null ->
                        stringResource(R.string.paywall_subscribe_trial,
                            trialDays, price)
                    price != null ->
                        stringResource(R.string.paywall_subscribe_price, price)
                    else -> stringResource(R.string.paywall_subscribe)
                }
            )
        }
        Text(
            if (trialDays > 0)
                stringResource(R.string.paywall_trial_line, trialDays)
            else stringResource(R.string.paywall_sub_line),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        // Existing owners on a new/reinstalled device land here —
        // restore re-queries Play's cached entitlements.
        TextButton(onClick = onRestore, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.paywall_restore))
        }
    }
}
