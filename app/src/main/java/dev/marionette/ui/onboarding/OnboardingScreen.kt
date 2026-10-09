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
import dev.marionette.ui.components.VoiceOrb
import dev.marionette.ui.settings.BackendFormContent
import dev.marionette.ui.settings.BackendViewModel
import dev.marionette.ui.theme.Brand
import kotlinx.coroutines.launch

enum class OnboardingStep { WELCOME, BACKEND, DONE }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnboardingScreen(onDone: () -> Unit, backend: BackendViewModel = viewModel()) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val step = OnboardingStep.entries[index]
    val form by backend.form.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    BackHandler(enabled = index > 0) { index-- }

    val canContinue = when (step) {
        OnboardingStep.BACKEND -> form.passed
        else -> true
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
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
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
                    OnboardingStep.BACKEND -> {
                        StepHeader(R.string.onboarding_backend_title, R.string.onboarding_backend_body)
                        BackendFormContent(
                            form = form,
                            onProvider = backend::selectProvider,
                            onBaseUrl = backend::setBaseUrl,
                            onApiKey = backend::setApiKey,
                            onModel = backend::setModel,
                            onLoadModels = backend::loadModels,
                            onTest = backend::test,
                        )
                    }
                    OnboardingStep.DONE -> Hero(R.string.onboarding_done_title, R.string.onboarding_done_body)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index > 0) TextButton(onClick = { index-- }) { Text(stringResource(R.string.action_back)) }
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
                modifier = Modifier.height(56.dp),
            ) {
                Text(
                    stringResource(
                        when (step) {
                            OnboardingStep.WELCOME -> R.string.onboarding_start
                            OnboardingStep.DONE -> R.string.onboarding_finish
                            else -> R.string.action_continue
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
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
            stringResource(title),
            style = MaterialTheme.typography.displaySmall.copy(brush = Brand.textBrush()),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun StepHeader(title: Int, body: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(24.dp))
}
