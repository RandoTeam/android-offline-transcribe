package com.voiceping.offlinetranscription.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.voiceping.offlinetranscription.service.WhisperEngine
import com.voiceping.offlinetranscription.ui.setup.ModelSetupScreen
import com.voiceping.offlinetranscription.ui.setup.ModelSetupViewModel
import com.voiceping.offlinetranscription.ui.transcription.TranscriptionScreen
import com.voiceping.offlinetranscription.ui.transcription.TranscriptionViewModel
import com.voiceping.offlinetranscription.model.ModelState
import com.voiceping.offlinetranscription.ui.history.HistoryScreen

object Routes {
    const val SETUP = "setup"
    const val TRANSCRIBE = "transcribe"
    const val HISTORY = "history"
}

@Composable
fun AppNavigation(
    engine: WhisperEngine
) {
    val modelState by engine.modelState.collectAsState()
    val navController = rememberNavController()

    val startDestination = if (modelState == ModelState.Loaded) Routes.TRANSCRIBE else Routes.SETUP

    LaunchedEffect(modelState) {
        when (modelState) {
            ModelState.Loaded -> {
                val currentRoute = navController.currentBackStackEntry?.destination?.route
                if (currentRoute == Routes.SETUP) {
                    navController.navigate(Routes.TRANSCRIBE) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                }
            }
            else -> Unit
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.SETUP) {
            val viewModel = remember { ModelSetupViewModel(engine) }
            ModelSetupScreen(viewModel = viewModel)
        }

        composable(Routes.TRANSCRIBE) {
            val viewModel = remember { TranscriptionViewModel(engine) }
            TranscriptionScreen(
                viewModel = viewModel,
                onChangeModel = {
                    engine.unloadModel()
                    engine.clearError()
                    navController.navigate(Routes.SETUP) {
                        popUpTo(Routes.TRANSCRIBE) { inclusive = true }
                    }
                },
                onOpenHistory = { navController.navigate(Routes.HISTORY) }
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(onBack = { navController.popBackStack() })
        }
    }
}
