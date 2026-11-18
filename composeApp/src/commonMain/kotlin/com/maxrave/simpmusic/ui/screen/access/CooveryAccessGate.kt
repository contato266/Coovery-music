package com.maxrave.simpmusic.ui.screen.access

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maxrave.simpmusic.account.CooveryAccessFailure
import com.maxrave.simpmusic.account.CooveryAccessResult
import com.maxrave.simpmusic.account.CooveryAccessState
import com.maxrave.simpmusic.account.CooveryAccessStore
import com.maxrave.simpmusic.ui.theme.tvFocusable
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.circle_app_icon
import simpmusic.composeapp.generated.resources.coovery_access_checking
import simpmusic.composeapp.generated.resources.coovery_access_create
import simpmusic.composeapp.generated.resources.coovery_access_email
import simpmusic.composeapp.generated.resources.coovery_access_email_in_use
import simpmusic.composeapp.generated.resources.coovery_access_inactive
import simpmusic.composeapp.generated.resources.coovery_access_invalid_credentials
import simpmusic.composeapp.generated.resources.coovery_access_invalid_email
import simpmusic.composeapp.generated.resources.coovery_access_login_disabled
import simpmusic.composeapp.generated.resources.coovery_access_network
import simpmusic.composeapp.generated.resources.coovery_access_password
import simpmusic.composeapp.generated.resources.coovery_access_retry
import simpmusic.composeapp.generated.resources.coovery_access_sign_in
import simpmusic.composeapp.generated.resources.coovery_access_sign_out
import simpmusic.composeapp.generated.resources.coovery_access_subtitle
import simpmusic.composeapp.generated.resources.coovery_access_title
import simpmusic.composeapp.generated.resources.coovery_access_too_many
import simpmusic.composeapp.generated.resources.coovery_access_unknown
import simpmusic.composeapp.generated.resources.coovery_access_unreadable
import simpmusic.composeapp.generated.resources.coovery_access_weak_password

@Composable
internal fun CooveryAccessGate(
    state: CooveryAccessState,
    store: CooveryAccessStore,
    onState: (CooveryAccessState) -> Unit,
) {
    when (state) {
        CooveryAccessState.Checking -> CooveryAccessBusy(stringResource(Res.string.coovery_access_checking))
        CooveryAccessState.SignedOut ->
            CooveryAccessForm(
                store = store,
                onState = onState,
            )
        is CooveryAccessState.Inactive ->
            CooveryAccessWaiting(
                message =
                    stringResource(
                        if (state.unreadable) {
                            Res.string.coovery_access_unreadable
                        } else {
                            Res.string.coovery_access_inactive
                        },
                    ),
                email = state.email,
                store = store,
                onState = onState,
            )
        is CooveryAccessState.Active -> Unit
    }
}

@Composable
private fun CooveryAccessBusy(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CooveryAccessForm(
    store: CooveryAccessStore,
    onState: (CooveryAccessState) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<CooveryAccessFailure?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val passwordFocus = remember { FocusRequester() }
    val canSubmit = email.contains("@") && password.length >= 6 && !busy

    fun submit(create: Boolean) {
        if (!canSubmit) return
        busy = true
        failure = null
        scope.launch {
            val result =
                if (create) {
                    store.signUp(email, password)
                } else {
                    store.signIn(email, password)
                }
            busy = false
            when (result) {
                is CooveryAccessResult.Ready -> onState(result.state)
                is CooveryAccessResult.Failed -> failure = result.reason
            }
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(Res.drawable.circle_app_icon),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(Res.string.coovery_access_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(Res.string.coovery_access_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(Res.string.coovery_access_email)) },
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(passwordFocus),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            label = { Text(stringResource(Res.string.coovery_access_password)) },
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions = KeyboardActions(onDone = { submit(create = false) }),
        )
        failure?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(it.message),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { submit(create = false) },
            enabled = canSubmit,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .tvFocusable(),
        ) {
            Text(stringResource(Res.string.coovery_access_sign_in))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { submit(create = true) },
            enabled = canSubmit,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .tvFocusable(),
        ) {
            Text(stringResource(Res.string.coovery_access_create))
        }
        if (busy) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun CooveryAccessWaiting(
    message: String,
    email: String,
    store: CooveryAccessStore,
    onState: (CooveryAccessState) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        if (email.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                email,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (busy) return@Button
                busy = true
                scope.launch {
                    onState(store.restore())
                    busy = false
                }
            },
            enabled = !busy,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .tvFocusable(),
        ) {
            Text(stringResource(Res.string.coovery_access_retry))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                scope.launch { onState(store.signOut()) }
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .tvFocusable(),
        ) {
            Text(stringResource(Res.string.coovery_access_sign_out))
        }
    }
}

private val CooveryAccessFailure.message
    get() =
        when (this) {
            CooveryAccessFailure.InvalidCredentials -> Res.string.coovery_access_invalid_credentials
            CooveryAccessFailure.EmailInUse -> Res.string.coovery_access_email_in_use
            CooveryAccessFailure.WeakPassword -> Res.string.coovery_access_weak_password
            CooveryAccessFailure.InvalidEmail -> Res.string.coovery_access_invalid_email
            CooveryAccessFailure.EmailLoginDisabled -> Res.string.coovery_access_login_disabled
            CooveryAccessFailure.TooManyAttempts -> Res.string.coovery_access_too_many
            CooveryAccessFailure.Network -> Res.string.coovery_access_network
            CooveryAccessFailure.Unknown -> Res.string.coovery_access_unknown
        }
