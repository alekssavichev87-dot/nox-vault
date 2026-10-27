package com.noxvault.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavController

/** Маршруты приложения. Доступ к ним возможен только в разблокированном графе. */
object Routes {
    const val SETUP = "setup"
    const val UNLOCK = "unlock"
    const val HOME = "home"
    const val VAULT = "vault"
    const val GENERATOR = "generator"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{itemId}"
    const val EDIT = "edit"
    const val CSV_IMPORT = "csvImport"

    fun vault(category: String? = null): String =
        if (category == null) VAULT else "$VAULT?category=$category"

    fun detail(itemId: String): String = "detail/$itemId"

    fun edit(itemId: String? = null, category: String? = null): String {
        val params = mutableListOf<String>()
        if (itemId != null) params += "itemId=$itemId"
        if (category != null) params += "category=$category"
        return if (params.isEmpty()) EDIT else "$EDIT?${params.joinToString("&")}"
    }

    val mainRoutes = setOf(HOME, VAULT, GENERATOR, SETTINGS)
}

@Composable
fun NoxBottomBar(navController: NavController, currentRoute: String?) {
    // destination.route содержит шаблон с параметрами ("vault?category=…") — сравниваем базовую часть.
    val baseRoute = currentRoute?.substringBefore('?')
    data class Dest(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

    val destinations = listOf(
        Dest(Routes.HOME, "Главная", Icons.Filled.Home),
        Dest(Routes.VAULT, "Хранилище", Icons.Filled.Storage),
        Dest(Routes.GENERATOR, "Генератор", Icons.Filled.Password),
        Dest(Routes.SETTINGS, "Настройки", Icons.Filled.Settings)
    )
    NavigationBar {
        destinations.forEach { dest ->
            NavigationBarItem(
                selected = baseRoute == dest.route,
                onClick = {
                    if (baseRoute != dest.route) {
                        navController.navigate(dest.route) {
                            popUpTo(Routes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(dest.icon, contentDescription = dest.label) },
                label = { Text(dest.label) }
            )
        }
    }
}
