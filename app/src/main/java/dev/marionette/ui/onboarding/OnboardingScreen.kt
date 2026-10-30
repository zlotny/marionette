package dev.marionette.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.marionette.MarionetteApp
import dev.marionette.R
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.marionette.ui.agentName
import dev.marionette.access.Access
import dev.marionette.settings.AppSettings
import dev.marionette.ui.access.AccessList
import dev.marionette.ui.access.rememberAccessState
import dev.marionette.ui.components.ChoiceRow
import dev.marionette.ui.components.SettingsGroup
import dev.marionette.ui.components.SettingsIcon
import dev.marionette.ui.components.VoiceOrb
import dev.marionette.ui.voice.VoiceTestButton
import dev.marionette.ui.voice.SpeechEnrollment
import dev.marionette.ui.voice.WakeEnrollment
import dev.marionette.ui.voice.languageOptions
import dev.marionette.voice.VoiceBus
import dev.marionette.voice.VoicePhase
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.ui.platform.LocalContext
import dev.marionette.ui.settings.BackendSetup
import dev.marionette.ui.settings.ProviderPicker
import dev.marionette.ui.settings.actions
import androidx.compose.runtime.remember
import dev.marionette.ui.settings.BackendViewModel
import dev.marionette.ui.theme.Brand
import kotlinx.coroutines.launch

enum class OnboardingStep { WELCOME, NAME, PROVIDER, BACKEND, PERMISSIONS, VOICE, WAKE, DONE }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnboardingScreen(onDone: () -> Unit, backend: BackendViewModel = viewModel()) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val step = OnboardingStep.entries[index]
    val form by backend.form.collectAsStateWithLifecycle()
    val backendActions = remember(backend) { backend.actions() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by MarionetteApp.graph.settings.settings.collectAsStateWithLifecycle()
    val voice by VoiceBus.status.collectAsStateWithLifecycle()
    val access = rememberAccessState()
    BackHandler(enabled = index > 0) { index-- }

    val accesses = Access.relevant(context, settings)
    var name by rememberSaveable { mutableStateOf(settings.agentName) }
    val canContinue = when (step) {
        OnboardingStep.NAME -> name.isNotBlank()
        OnboardingStep.BACKEND -> form.passed
        OnboardingStep.PERMISSIONS -> accesses.filter { it.essential }.all(access::isGranted)
        OnboardingStep.WAKE -> voice.phase != VoicePhase.ENROLLING
        else -> true
    }
    val update: ((AppSettings) -> AppSettings) -> Unit = { transform ->
        scope.launch { MarionetteApp.graph.settings.update { transform(it) } }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        if (step != OnboardingStep.WELCOME) {
            LinearWavyProgressIndicator(
                progress = { index / (OnboardingStep.entries.size - 1f) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
        val dir = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
        val scheme = MaterialTheme.motionScheme
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = if (targetState.ordinal > initialState.ordinal) dir else -dir
                (slideInHorizontally(scheme.defaultSpatialSpec()) { forward * it / 3 } + fadeIn(scheme.defaultEffectsSpec())) togetherWith
                    (slideOutHorizontally(scheme.defaultSpatialSpec()) { -forward * it / 3 } + fadeOut(scheme.fastEffectsSpec()))
            },
            modifier = Modifier.weight(1f),
            label = "onboarding",
        ) { current ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = if (current == OnboardingStep.WELCOME || current == OnboardingStep.DONE) Arrangement.Center else Arrangement.Top,
            ) {
                when (current) {
                    OnboardingStep.WELCOME -> Hero(R.string.onboarding_welcome_title, R.string.onboarding_welcome_body)
                    OnboardingStep.NAME -> {
                        StepHeader(R.string.onboarding_name_title, R.string.onboarding_name_body)
                        OutlinedTextField(
                            value = name,
                            onValueChange = { value ->
                                name = value.take(AppSettings.MAX_AGENT_NAME)
                                name.trim().takeIf { it.isNotEmpty() }?.let { trimmed -> update { it.copy(agentName = trimmed) } }
                            },
                            label = { Text(stringResource(R.string.agent_name_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OnboardingStep.PROVIDER -> {
                        StepHeader(R.string.onboarding_provider_title, R.string.onboarding_provider_body)
                        ProviderPicker(form.provider, backend::selectProvider)
                    }
                    OnboardingStep.BACKEND -> {
                        Text(stringResource(R.string.onboarding_backend_title, form.provider.title), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(24.dp))
                        BackendSetup(form, backendActions, compact = true)
                    }
                    OnboardingStep.PERMISSIONS -> {
                        StepHeader(R.string.onboarding_permissions_title, R.string.onboarding_permissions_body)
                        AccessList(stringResource(R.string.access_group_essential), accesses.filter { it.essential }, access)
                        Spacer(Modifier.height(16.dp))
                        AccessList(stringResource(R.string.access_group_optional), accesses.filterNot { it.essential }, access)
                    }
                    OnboardingStep.VOICE -> {
                        StepHeader(R.string.onboarding_voice_title, R.string.onboarding_voice_body)
                        SettingsGroup(null) {
                            item { i, n ->
                                ChoiceRow(
                                    i, n,
                                    headline = stringResource(R.string.voice_language),
                                    options = languageOptions(),
                                    selected = settings.language,
                                    onSelect = { tag -> update { it.copy(language = tag, ttsVoice = "") } },
                                    leading = { SettingsIcon(Icons.Rounded.Translate) },
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        VoiceTestButton(settings.language, settings.ttsVoice, settings.speechRate)
                    }
                    OnboardingStep.WAKE -> {
                        StepHeader(R.string.onboarding_wake_title, R.string.onboarding_wake_body)
                        WakeEnrollment(
                            savedPhrase = settings.wakePhrase,
                            onPhrase = { phrase -> update { it.copy(wakePhrase = phrase) } },
                        )
                        if (voice.templateCount > 0) {
                            Spacer(Modifier.height(16.dp))
                            SpeechEnrollment()
                        }
                    }
                    OnboardingStep.DONE -> Hero(R.string.onboarding_done_title, R.string.onboarding_done_body)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index > 0) TextButton(onClick = { index-- }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_back)) }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    if (step == OnboardingStep.DONE) {
                        scope.launch {
                            MarionetteApp.graph.settings.update { it.copy(onboarded = true) }
                            onDone()
                        }
                    } else {
                        index++
                    }
                },
                enabled = canContinue,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.heightIn(min = 56.dp),
            ) {
                Text(
                    stringResource(
                        when (step) {
                            OnboardingStep.WELCOME -> R.string.onboarding_start
                            OnboardingStep.DONE -> R.string.onboarding_finish
                            OnboardingStep.WAKE -> if (voice.templateCount > 0) R.string.action_continue else R.string.action_skip
                            else -> R.string.action_continue
                        },
                        agentName(),
                    ),
                )
            }
        }
    }
}

@Composable
private fun Hero(title: Int, body: Int) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        VoiceOrb(size = 180.dp)
        Spacer(Modifier.height(40.dp))
        Text(
            stringResource(title, agentName()),
            style = MaterialTheme.typography.displaySmall.copy(brush = Brand.textBrush()),
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(body, agentName()),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun StepHeader(title: Int, body: Int) {
    Text(stringResource(title, agentName()), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(8.dp))
    Text(stringResource(body, agentName()), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(24.dp))
}
