package com.yunlu.kongjian

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yunlu.kongjian.feature.appdetail.AppDetailScreen
import com.yunlu.kongjian.feature.applist.AppListScreen
import com.yunlu.kongjian.feature.settings.SettingsScreen
import com.yunlu.kongjian.feature.storage.StorageScreen
import com.yunlu.kongjian.ui.theme.AppManagerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppManagerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = "list") {
                        composable("list") {
                            AppListScreen(
                                // Package names are [a-zA-Z0-9_.] only, so they are
                                // safe to embed directly in a navigation route.
                                onAppClick = { pkg ->
                                    navController.navigate("detail/$pkg")
                                },
                                onOpenSettings = { navController.navigate("settings") },
                                onOpenStorage = { navController.navigate("storage") },
                            )
                        }
                        composable(
                            route = "detail/{packageName}",
                            arguments = listOf(
                                navArgument("packageName") { type = NavType.StringType }
                            ),
                        ) {
                            AppDetailScreen(onBack = { navController.popBackStack() })
                        }
                        composable("settings") {
                            SettingsScreen(onBack = { navController.popBackStack() })
                        }
                        composable("storage") {
                            StorageScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
