package com.reka.remoteplay.feature.auth.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reka.remoteplay.ui.theme.*

@Composable
fun LoginScreen(
    state: LoginUiState,
    onRelayUrlChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onToggleMode: () -> Unit,
    onSubmit: () -> Unit,
    onSkip: () -> Unit
) {
    var showAdvanced by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(AppBackgroundDark, AppBackgroundMid, AppBackgroundDark)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(56.dp))

            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = null,
                tint = AppAccent,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (state.isRegistering) "Create Account" else "Sign In",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = AppTextPrimary
            )
            Text(
                text = if (state.isRegistering) "Register to use relay connection" else "Sign in to connect via relay",
                fontSize = 13.sp,
                color = AppTextTertiary
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Main form card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = AppSurface)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    OutlinedTextField(
                        value = state.email,
                        onValueChange = onEmailChange,
                        label = { Text("Email", color = AppTextTertiary, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.Email, null, tint = AppTextTertiary) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                        colors = loginFieldColors()
                    )

                    AnimatedVisibility(visible = state.isRegistering) {
                        Column {
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = state.username,
                                onValueChange = onUsernameChange,
                                label = { Text("Username", color = AppTextTertiary, fontSize = 13.sp) },
                                leadingIcon = { Icon(Icons.Default.Person, null, tint = AppTextTertiary) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = loginFieldColors()
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = onPasswordChange,
                        label = { Text("Password", color = AppTextTertiary, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.Lock, null, tint = AppTextTertiary) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = loginFieldColors()
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Advanced relay URL
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = AppSurface)
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    TextButton(
                        onClick = { showAdvanced = !showAdvanced },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Icon(
                            if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = AppTextTertiary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Advanced — Relay Server", color = AppTextTertiary, fontSize = 13.sp)
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    AnimatedVisibility(visible = showAdvanced) {
                        Column {
                            OutlinedTextField(
                                value = state.relayUrl,
                                onValueChange = onRelayUrlChange,
                                label = { Text("Relay URL", color = AppTextTertiary, fontSize = 13.sp) },
                                leadingIcon = { Icon(Icons.Default.Link, null, tint = AppTextTertiary) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                colors = loginFieldColors()
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }

            // Error display
            AnimatedVisibility(visible = state.error != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AppRedBg)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Error, null, tint = AppRed, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(state.error ?: "", color = AppRedLight, fontSize = 13.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Submit button
            Button(
                onClick = onSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !state.isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = AppAccent)
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = AppTextPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = if (state.isRegistering) "Create Account" else "Sign In",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Toggle login/register
            TextButton(onClick = onToggleMode) {
                Text(
                    text = if (state.isRegistering) "Already have an account? Sign In" else "No account? Create one",
                    color = AppAccent,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Skip button
            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppBorder)
            ) {
                Icon(
                    Icons.Default.ArrowForward,
                    contentDescription = null,
                    tint = AppTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Skip — Connect by ID", color = AppTextSecondary, fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun loginFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AppAccent,
    unfocusedBorderColor = AppBorder,
    focusedTextColor = AppTextPrimary,
    unfocusedTextColor = AppTextSecondary,
    cursorColor = AppAccent
)
