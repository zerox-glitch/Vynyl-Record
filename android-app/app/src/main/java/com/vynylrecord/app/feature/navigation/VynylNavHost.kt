package com.vynylrecord.app.feature.navigation

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vynylrecord.app.R
import com.vynylrecord.app.core.data.prefs.VynylSettings
import com.vynylrecord.app.core.design.BrassPanel
import com.vynylrecord.app.core.design.SectionHeading
import com.vynylrecord.app.core.design.VynylColors
import com.vynylrecord.app.core.design.VynylIcons
import com.vynylrecord.app.core.design.VynylPrimaryButton
import com.vynylrecord.app.core.design.VynylType
import com.vynylrecord.app.core.security.AppLock
import com.vynylrecord.app.feature.library.LibraryScreen
import com.vynylrecord.app.feature.onboarding.OnboardingScreen
import com.vynylrecord.app.feature.player.PlayerScreen
import com.vynylrecord.app.feature.settings.SettingsScreen
import com.vynylrecord.app.feature.soundlab.SoundLabScreen
import com.vynylrecord.app.feature.studio.StudioScreen

/**
 * Where everything is.
 *
 * Four destinations behind a bottom bar — Studio, Sound Lab, Master Vault, Settings — plus the player, which
 * is *not* a tab: a record is opened from the Vault, and the player is somewhere you go into and come back
 * from. You press records in the Studio, you keep them in the Vault, and you listen to one at a time; the
 * navigation says the same thing the product does.
 *
 * Two gates stand in front of all of it, in this order: the lock, if the user turned it on, and the three
 * onboarding pages, which are shown once and never again.
 */
object Routes {
    const val STUDIO = "studio"
    const val STUDIO_RECORD = "studio/{recordId}"
    const val SOUND_LAB = "soundlab"
    const val VAULT = "vault"
    const val SETTINGS = "settings"
    const val PLAYER = "player/{recordId}"

    fun studio(recordId: String): String = "studio/$recordId"

    fun player(recordId: String): String = "player/$recordId"

    /** The routes that show a bottom bar, with the prefix the bar matches on. */
    val tabs = listOf(STUDIO, SOUND_LAB, VAULT, SETTINGS)
}

@Composable
fun VynylNavHost(
    settings: VynylSettings,
    locked: Boolean,
    lockAvailability: AppLock.Availability,
    onUnlockRequested: (ComponentActivity) -> Unit,
    onActivity: () -> ComponentActivity?,
    pendingImportPath: String?,
    onImportConsumed: () -> Unit,
) {
    if (locked && settings.biometricLock) {
        LockScreen(
            availability = lockAvailability,
            onUnlock = { onActivity()?.let(onUnlockRequested) },
        )
        return
    }

    val navController = rememberNavController()

    if (!settings.onboardingComplete) {
        // The pages stand alone: a user who has not seen them has nothing to navigate to yet, and a bottom
        // bar behind a first-run screen is a menu for a library that is not there.
        OnboardingScreen(onFinished = { navController.navigate(Routes.STUDIO) })
        return
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBar = currentRoute in Routes.tabs

    Scaffold(
        containerColor = VynylColors.Obsidian,
        bottomBar = {
            AnimatedVisibility(
                visible = showBar,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                VynylBottomBar(navController = navController, currentRoute = currentRoute)
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(bottom = if (showBar) insets.calculateBottomPadding() else 0.dp)) {
            NavHost(
                navController = navController,
                startDestination = if (pendingImportPath != null) Routes.VAULT else Routes.STUDIO,
            ) {
                composable(Routes.STUDIO) {
                    StudioScreen(
                        recordId = null,
                        onOpenPlayer = { id -> navController.navigate(Routes.player(id)) },
                        onOpenVault = { navController.navigate(Routes.VAULT) { launchSingleTop = true } },
                    )
                }
                composable(
                    route = Routes.STUDIO_RECORD,
                    arguments = listOf(navArgument("recordId") { type = NavType.StringType }),
                ) { entry ->
                    StudioScreen(
                        recordId = entry.arguments?.getString("recordId"),
                        onOpenPlayer = { id -> navController.navigate(Routes.player(id)) },
                        onOpenVault = { navController.navigate(Routes.VAULT) { launchSingleTop = true } },
                    )
                }
                composable(Routes.SOUND_LAB) { SoundLabScreen() }
                composable(Routes.VAULT) {
                    LibraryScreen(
                        pendingImportPath = pendingImportPath,
                        onImportConsumed = onImportConsumed,
                        onOpenRecord = { id -> navController.navigate(Routes.player(id)) },
                        onEditRecord = { id -> navController.navigate(Routes.studio(id)) },
                        onNewRecord = { navController.navigate(Routes.STUDIO) { launchSingleTop = true } },
                    )
                }
                composable(Routes.SETTINGS) { SettingsScreen() }
                composable(
                    route = Routes.PLAYER,
                    arguments = listOf(navArgument("recordId") { type = NavType.StringType }),
                ) { entry ->
                    PlayerScreen(
                        recordId = entry.arguments?.getString("recordId") ?: "",
                        onBack = { navController.popBackStack() },
                        onEdit = { id -> navController.navigate(Routes.studio(id)) },
                    )
                }
            }
        }
    }
}

/**
 * The bottom bar.
 *
 * Obsidian with a brass hairline above it, and the selected tab in amber. The bar pads itself for the
 * gesture inset so nothing is drawn underneath the system's own bar, and the deck is edge-to-edge above it.
 */
@Composable
private fun VynylBottomBar(navController: NavHostController, currentRoute: String?) {
    NavigationBar(
        containerColor = VynylColors.DeepStone,
        contentColor = VynylColors.Cream,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        for (tab in Tabs.ordered) {
            val selected = currentRoute == tab.route || currentRoute?.startsWith("${tab.route}/") == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        navController.navigate(tab.route) {
                            // The bar is a set of siblings, not a stack: choosing a tab does not leave the
                            // previous one behind it.
                            popUpTo(Routes.STUDIO) { inclusive = tab.route == Routes.STUDIO }
                            launchSingleTop = true
                        }
                    }
                },
                icon = { Icon(tab.icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
                label = { Text(tab.label, style = VynylType.caption) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = VynylColors.Obsidian,
                    selectedTextColor = VynylColors.AmberBright,
                    indicatorColor = VynylColors.Amber,
                    unselectedIconColor = VynylColors.Muted,
                    unselectedTextColor = VynylColors.Muted,
                ),
            )
        }
    }
}

/** The four tabs with their labels and icons, in one place. */
private enum class Tabs(val route: String, val labelRes: Int) {
    STUDIO(Routes.STUDIO, R.string.tab_studio),
    SOUND_LAB(Routes.SOUND_LAB, R.string.tab_sound_lab),
    VAULT(Routes.VAULT, R.string.tab_vault),
    SETTINGS(Routes.SETTINGS, R.string.tab_settings),
    ;

    val label: String
        @Composable get() = stringResource(labelRes)

    val icon: ImageVector
        get() = when (this) {
            STUDIO -> VynylIcons.Studio
            SOUND_LAB -> VynylIcons.SoundLab
            VAULT -> VynylIcons.Vault
            SETTINGS -> VynylIcons.Settings
        }

    companion object {
        val ordered: List<Tabs> = entries.toList()
    }
}

/**
 * The lock screen.
 *
 * One button: unlock, or — where the device has no way to authenticate — an explanation and a way into the
 * app to turn the lock off. That second case matters: somebody who enabled the lock and then removed their
 * fingerprint must not be shut out of their own recordings for good.
 */
@Composable
private fun LockScreen(availability: AppLock.Availability, onUnlock: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(VynylColors.Obsidian)
            .statusBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Vynyl Record", style = VynylType.display, color = VynylColors.Cream)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your recordings are locked on this device.",
            style = VynylType.body,
            color = VynylColors.Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        BrassPanel {
            SectionHeading(title = "Unlock", subtitle = availability.message)
            VynylPrimaryButton(
                text = if (availability.canEnable) "Unlock with fingerprint or PIN" else "Try again",
                onClick = onUnlock,
            )
        }
    }
}
