package com.th3web.lean.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.th3web.lean.ui.theme.GlassBackdrop
import com.th3web.lean.ui.screen.AboutHub
import com.th3web.lean.ui.screen.AboutScreen
import com.th3web.lean.ui.screen.AppearanceHub
import com.th3web.lean.ui.screen.BackupScreen
import com.th3web.lean.ui.screen.ConnectionHub
import com.th3web.lean.ui.screen.DnsScreen
import com.th3web.lean.ui.screen.HomeScreen
import com.th3web.lean.ui.screen.IpTypeScreen
import com.th3web.lean.ui.screen.TunStackScreen
import com.th3web.lean.ui.screen.LanguageScreen
import com.th3web.lean.ui.screen.LicensesScreen
import com.th3web.lean.ui.screen.LogsScreen
import com.th3web.lean.ui.screen.OlcrtcBuilderScreen
import com.th3web.lean.ui.screen.PerAppScreen
import com.th3web.lean.ui.screen.PingScreen
import com.th3web.lean.ui.screen.ProviderHub
import com.th3web.lean.ui.screen.RuleSetsScreen
import com.th3web.lean.ui.screen.ServersScreen
import com.th3web.lean.ui.screen.SettingsScreen
import com.th3web.lean.ui.screen.appearance.AppearanceColorScreen
import com.th3web.lean.ui.screen.appearance.AppearanceFontsScreen
import com.th3web.lean.ui.screen.appearance.AppearanceHomeStyleScreen
import com.th3web.lean.ui.screen.appearance.AppearanceLabScreen
import com.th3web.lean.ui.screen.appearance.AppearanceMotionScreen
import com.th3web.lean.ui.screen.appearance.AppearanceRolesScreen
import com.th3web.lean.ui.screen.appearance.AppearanceServersStyleScreen
import com.th3web.lean.ui.screen.appearance.AppearanceShapeScreen
import com.th3web.lean.ui.screen.appearance.AppearanceSystemScreen

/**
 * Top-bar navigation (no bottom bar): Home is the root; Servers and Settings are
 * pushed from Home's top-bar actions and return via a back arrow. Each screen owns its own
 * scaffold, and each paints its own canvas ([com.th3web.lean.ui.theme.leanBackground]), so
 * the wallpaper travels with the screen through a transition, which is the intended look.
 * Hoisting it to the host made it stand still, and a screen sliding over a frozen picture
 * read as a slab moving over a backdrop rather than as one surface.
 */
@Composable
fun LeanRoot(
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        // Slide only, no cross-fade: fading one destination over another showed both at
        // once, which read as a flicker rather than as a transition.
        //
        // A push enters from the full width, not from a fifth of it. With the cross-fade
        // gone, the incoming screen is the only thing that covers the outgoing one, and
        // starting it at 20% while the outgoing slid 10% the other way left a strip of
        // bare window between them for the whole 280 ms. That strip is the flicker: it is
        // neither screen, so it shows whatever the window sits on. Entering at 100% keeps
        // the two edges touching for every frame of the animation.
        enterTransition = { slideInHorizontally(animationSpec = tween(280)) { it } },
        exitTransition = { slideOutHorizontally(animationSpec = tween(280)) { -it / 10 } },
        popEnterTransition = { slideInHorizontally(animationSpec = tween(280)) { -it / 10 } },
        // And a pop leaves across the full width, for a reason the push does not have:
        // predictive back seeks this transition with the gesture's progress, so where it
        // ends is where the screen ends up under the finger. At a quarter of the width the
        // screen being dismissed travelled a quarter and was then simply removed, it
        // vanished in place instead of finishing its way out, and no amount of gesture
        // could carry it further because there was nowhere further to go.
        popExitTransition = { slideOutHorizontally(animationSpec = tween(280)) { it } },
    ) {
        // GlassBackdrop marks the two destinations that let the wallpaper show through,
        // the rest cover it with an opaque canvas of their own. «Стекло» shows the fragment
        // of wallpaper lying under a panel, so it only means anything where the wallpaper
        // is visible; anywhere else it drew a floating patch of picture over a flat canvas,
        // which is what made the settings groups look wrongly see-through.
        composable(Routes.HOME) {
            GlassBackdrop {
                HomeScreen(
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onOpenServers = { navController.navigate(Routes.SERVERS) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
        }
        composable(Routes.SERVERS) {
            GlassBackdrop {
                ServersScreen(
                    onBack = { navController.popBackStack() },
                    onNavigate = { route -> navController.navigate(route) },
                )
            }
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigate = { route -> navController.navigate(route) },
                onBack = { navController.popBackStack() },
            )
        }
        // Consolidated settings hubs
        composable(Routes.HUB_APPEARANCE) {
            AppearanceHub(onBack = { navController.popBackStack() }, onNavigate = { navController.navigate(it) })
        }
        composable(Routes.HUB_CONNECTION) {
            ConnectionHub(onBack = { navController.popBackStack() }, onNavigate = { navController.navigate(it) })
        }
        composable(Routes.HUB_PROVIDER) {
            ProviderHub(onBack = { navController.popBackStack() }, onNavigate = { navController.navigate(it) })
        }
        composable(Routes.HUB_ABOUT) {
            AboutHub(onBack = { navController.popBackStack() }, onNavigate = { navController.navigate(it) })
        }
        // «Оформление» detail screens, one per section of the look.
        composable(Routes.APPEARANCE_COLOR) {
            AppearanceColorScreen(onBack = { navController.popBackStack() }, onNavigate = { navController.navigate(it) })
        }
        composable(Routes.APPEARANCE_FONTS) { AppearanceFontsScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.APPEARANCE_SHAPE) { AppearanceShapeScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.APPEARANCE_HOME) { AppearanceHomeStyleScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.APPEARANCE_SERVERS) { AppearanceServersStyleScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.APPEARANCE_MOTION) { AppearanceMotionScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.APPEARANCE_SYSTEM) {
            AppearanceSystemScreen(onBack = { navController.popBackStack() }, onNavigate = { navController.navigate(it) })
        }
        composable(Routes.APPEARANCE_LAB) {
            AppearanceLabScreen(onBack = { navController.popBackStack() }, onNavigate = { navController.navigate(it) })
        }
        composable(Routes.APPEARANCE_ROLES) { AppearanceRolesScreen(onBack = { navController.popBackStack() }) }

        composable(Routes.PING) { PingScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.DNS) { DnsScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.RULE_SETS) { RuleSetsScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.IP_TYPE) { IpTypeScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.TUN_STACK) { TunStackScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.PER_APP) { PerAppScreen(onBack = { navController.popBackStack() }) }

        composable(Routes.LOGS) { LogsScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.OLCRTC_NEW) {
            OlcrtcBuilderScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.ABOUT) {
            AboutScreen(
                onBack = { navController.popBackStack() },
                onOpenLicenses = { navController.navigate(Routes.LICENSES) },
            )
        }
        composable(Routes.LICENSES) { LicensesScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.LANGUAGE) { LanguageScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.BACKUP) { BackupScreen(onBack = { navController.popBackStack() }) }
    }
}
