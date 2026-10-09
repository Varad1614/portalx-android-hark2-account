package com.pravahax.portalx.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pravahax.portalx.core.ui.R
import com.pravahax.portalx.data.SessionUser
import com.pravahax.portalx.data.Validate
import com.pravahax.portalx.net.Fn
import com.pravahax.portalx.net.PortalException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun LoginScreen(notice: String? = null, onSignedIn: (SessionUser) -> Unit) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val haptics = rememberHaptics()
    // Survive rotation / process death. The password is deliberately NOT saveable (it would be written to the saved-state bundle).
    var workspace by rememberSaveable { mutableStateOf(repo.lastWorkspace()) }
    var userId by rememberSaveable { mutableStateOf(repo.lastUserId()) }
    var forgot by rememberSaveable { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf(notice) }
    // Client-side throttle after repeated failures (the server owns real lockout; this just stops hammering it).
    var failures by rememberSaveable { mutableIntStateOf(0) }
    var lockedUntil by rememberSaveable { mutableLongStateOf(0L) }

    fun submit() {
        if (loading) return
        Validate.login(workspace, userId, password)?.let { error = it; haptics.error(); return }
        val wait = (lockedUntil - System.currentTimeMillis()) / 1000
        if (wait > 0) { error = "Too many attempts. Try again in ${wait + 1} s."; haptics.error(); return }
        focus.clearFocus(); loading = true; error = null; info = null
        scope.launch {
            try { val u = repo.login(workspace, userId, password); password = ""; failures = 0; haptics.success(); onSignedIn(u) }
            catch (e: CancellationException) { throw e }
            catch (e: PortalException) {
                error = e.message; haptics.error()
                if (!e.offline && e.code !in 500..599) {
                    failures++
                    if (failures >= 3) lockedUntil = System.currentTimeMillis() + minOf(30_000L, 2_000L shl (failures - 3))
                }
            }
            catch (e: Exception) { error = "Sign-in failed. Please try again."; haptics.error() }
            finally { loading = false }
        }
    }

    fun requestReset() {
        if (loading) return
        if (workspace.isBlank() || userId.isBlank()) { error = "Enter your workspace and User ID or email."; return }
        focus.clearFocus(); loading = true; error = null
        scope.launch {
            try {
                repo.act(Fn.RequestPasswordReset, buildJsonObject { put("workspace", workspace.trim().lowercase()); put("identifier", userId.trim()) })
                forgot = false
                info = "Request processed. Check your registered work email, or contact your workspace administrator if you need urgent help."
            } catch (e: CancellationException) { throw e
            } catch (e: PortalException) { error = e.message ?: "Could not request a reset."
            } catch (e: Exception) { error = "Could not request a reset. Please try again."
            } finally { loading = false }
        }
    }

    // The web's mobile sign-in (.platform-login-card-wrap): parchment + gold radial glow, one white card
    // (1.5rem radius, slate-200 hairline, shadow-lifted) holding the brand, the heading and the form.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).goldGlow()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).systemBarsPadding().imePadding()
                .padding(horizontal = Space.lg, vertical = Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(Space.lg))
            Image(painterResource(R.drawable.logo_alpha_gold), "PRAVAHAx", Modifier.height(56.dp))
            Spacer(Modifier.height(Space.xl))
            Surface(
                Modifier.widthIn(max = 448.dp).fillMaxWidth()
                    .shadow(28.dp, MaterialTheme.shapes.extraLarge, clip = false, ambientColor = Web.ShadowInk.copy(alpha = 0.12f), spotColor = Web.ShadowInk.copy(alpha = 0.22f)),
                shape = MaterialTheme.shapes.extraLarge, color = Color.White.copy(alpha = 0.94f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(Space.xxl)) {
                    AnimatedContent(forgot, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "mode") { f ->
                        Column {
                            Kicker(if (f) "Account access" else "Welcome back")
                            Spacer(Modifier.height(Space.sm))
                            Text(if (f) "Reset your password" else "Sign in to your workspace", style = MaterialTheme.typography.headlineLarge,
                                modifier = Modifier.semantics { heading() })
                            Spacer(Modifier.height(Space.xs))
                            Text(if (f) "Enter your company workspace and User ID or email. We'll send a reset link to your registered work email."
                                else "Use your company workspace, User ID or email, and password.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(Space.xl))
                    AnimatedVisibility(info != null) { InfoBanner(info ?: "", Modifier.padding(bottom = Space.md)) }
                    AnimatedVisibility(error != null) { Box(Modifier.padding(bottom = Space.md)) { ErrorBanner(error ?: "") } }
                    OutlinedTextField(
                        workspace, { workspace = it.lowercase().filter { c -> !c.isWhitespace() }.take(63); error = null }, Modifier.fillMaxWidth(),
                        label = { Text("Company workspace") }, placeholder = { Text("e.g. pravahax") },
                        leadingIcon = { Icon(Icons.Outlined.Business, null) }, singleLine = true, shape = MaterialTheme.shapes.medium,
                        isError = error != null && workspace.isBlank(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }),
                    )
                    Spacer(Modifier.height(Space.md))
                    OutlinedTextField(
                        userId, { userId = it.trim().take(120); error = null },
                        Modifier.fillMaxWidth().autofill(listOf(androidx.compose.ui.autofill.AutofillType.Username)) { userId = it.trim().take(120) },
                        label = { Text("User ID or email") }, placeholder = { Text("e.g. PSE-00001") },
                        leadingIcon = { Icon(Icons.Outlined.Badge, null) }, singleLine = true, shape = MaterialTheme.shapes.medium,
                        isError = error != null && userId.isBlank(),
                        keyboardOptions = KeyboardOptions(imeAction = if (forgot) ImeAction.Send else ImeAction.Next, capitalization = if (forgot) KeyboardCapitalization.None else KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }, onSend = { requestReset() }),
                    )
                    if (!forgot) {
                        Spacer(Modifier.height(Space.md))
                        OutlinedTextField(
                            password, { password = it.take(256); error = null },
                            Modifier.fillMaxWidth().autofill(listOf(androidx.compose.ui.autofill.AutofillType.Password)) { password = it.take(256) },
                            label = { Text("Password") }, leadingIcon = { Icon(Icons.Outlined.Lock, null) }, singleLine = true,
                            shape = MaterialTheme.shapes.medium, isError = error != null && password.isEmpty(),
                            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { show = !show }) {
                                    Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (show) "Hide password" else "Show password")
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                            keyboardActions = KeyboardActions(onGo = { submit() }),
                        )
                    }
                    Spacer(Modifier.height(Space.xl))
                    Button(
                        onClick = { if (forgot) requestReset() else submit() },
                        enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), disabledContentColor = MaterialTheme.colorScheme.onPrimary),
                    ) { BusyLabel(loading, if (forgot) "Send reset link" else "Sign in", MaterialTheme.colorScheme.onPrimary) }
                    TextButton(onClick = { forgot = !forgot; error = null }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = Space.sm)) {
                        Text(if (forgot) "Back to sign in" else "Forgot password?")
                    }
                }
            }
            Spacer(Modifier.height(Space.xl))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.VerifiedUser, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(Space.xs))
                Text("Private workspace · Encrypted on this device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
