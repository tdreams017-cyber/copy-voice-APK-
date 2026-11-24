package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ai.NabraAiService
import com.example.ai.VoiceCloneApiClient
import com.example.audio.AudioPlayerManager
import com.example.audio.AudioRecorderManager
import com.example.auth.AuthScreen
import com.example.auth.signOutUser
import com.example.data.ApiKeyPreferences
import com.example.data.NabraRepository
import com.example.ui.NabraViewModel
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.TranscribeScreen
import com.example.ui.screens.VoiceStudioScreen
import com.example.ui.theme.NabraTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

private enum class NabraDestination(
    val route: String,
    val labelAr: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    TRANSCRIBE(
        route = "transcribe",
        labelAr = "نسخ الصوت",
        selectedIcon = Icons.Filled.Mic,
        unselectedIcon = Icons.Outlined.Mic
    ),
    LIBRARY(
        route = "library",
        labelAr = "النصوص",
        selectedIcon = Icons.Filled.Folder,
        unselectedIcon = Icons.Outlined.Folder
    ),
    VOICE_STUDIO(
        route = "voice_studio",
        labelAr = "صوتي",
        selectedIcon = Icons.Filled.RecordVoiceOver,
        unselectedIcon = Icons.Outlined.RecordVoiceOver
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure App Check with launch intent before any UI, ViewModels, or Firebase operations
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                NabraTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        AppContent()
                    }
                }
            }
        }
    }
}

@Composable
fun AppContent() {
    var currentUser by remember { mutableStateOf(Firebase.auth.currentUser) }

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            currentUser = auth.currentUser
        }
        Firebase.auth.addAuthStateListener(listener)
        onDispose { Firebase.auth.removeAuthStateListener(listener) }
    }

    val user = currentUser
    if (user == null) {
        AuthScreen(onAuthSuccess = { currentUser = Firebase.auth.currentUser })
    } else {
        key(user.uid) {
            NabraAuthenticatedShell(currentUserId = user.uid)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NabraAuthenticatedShell(currentUserId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    val navController = rememberNavController()

    val viewModel: NabraViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                NabraViewModel(
                    repository = NabraRepository(db),
                    aiService = NabraAiService(),
                    voiceCloneClient = VoiceCloneApiClient(),
                    apiKeyPreferences = ApiKeyPreferences(app.applicationContext),
                    recorder = AudioRecorderManager(app.applicationContext),
                    player = AudioPlayerManager(app.applicationContext),
                    currentUserId = currentUserId
                )
            }
        }
    )

    LaunchedEffect(viewModel) {
        viewModel.statusMessages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val navigateToTab: (NabraDestination) -> Unit = { destination ->
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = "نَبرة",
                            style = MaterialTheme.typography.headlineSmall
                        )
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
                    actions = {
                        IconButton(
                            onClick = {
                                signOutUser(
                                    credentialManager = credentialManager,
                                    scope = scope,
                                    onBeforeSignOut = { viewModel.cleanupAudioOnSignOut() }
                                )
                            },
                            modifier = Modifier.testTag("sign_out_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Logout,
                                contentDescription = "تسجيل الخروج"
                            )
                        }
                    }
                )
            },
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        NabraDestination.entries.forEach { dest ->
                            val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateToTab(dest) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.labelAr
                                    )
                                },
                                label = { Text(dest.labelAr) },
                                modifier = Modifier.testTag("nav_${dest.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        NabraDestination.entries.forEach { dest ->
                            val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToTab(dest) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.labelAr
                                    )
                                },
                                label = { Text(dest.labelAr) },
                                modifier = Modifier.testTag("nav_rail_${dest.route}")
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Box(modifier = Modifier.weight(1f)) {
                    NavHost(
                        navController = navController,
                        startDestination = NabraDestination.TRANSCRIBE.route
                    ) {
                        composable(NabraDestination.TRANSCRIBE.route) {
                            TranscribeScreen(
                                viewModel = viewModel,
                                onSendToVoiceStudio = { text ->
                                    viewModel.sendTextToVoiceStudio(text)
                                    navigateToTab(NabraDestination.VOICE_STUDIO)
                                },
                                onExportSuccessMessage = { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            )
                        }
                        composable(NabraDestination.LIBRARY.route) {
                            LibraryScreen(
                                viewModel = viewModel,
                                onSendToVoiceStudio = { text ->
                                    viewModel.sendTextToVoiceStudio(text)
                                    navigateToTab(NabraDestination.VOICE_STUDIO)
                                },
                                onStatusMessage = { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            )
                        }
                        composable(NabraDestination.VOICE_STUDIO.route) {
                            VoiceStudioScreen(
                                viewModel = viewModel,
                                onStatusMessage = { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
