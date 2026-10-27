package com.noxvault.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.noxvault.app.domain.model.ItemCategory
import com.noxvault.app.ui.components.CategoryPickerDialog
import com.noxvault.app.ui.components.EmptyState
import com.noxvault.app.ui.components.ItemCard
import com.noxvault.app.ui.components.OptionPickerDialog
import com.noxvault.app.ui.navigation.Routes
import com.noxvault.app.viewmodel.VaultViewModel

private enum class SortMode(val label: String) {
    UPDATED("Сначала изменённые"),
    CREATED("Сначала новые"),
    TITLE("По названию (А–Я)"),
    FAVORITES("Избранные сверху")
}

private const val FOLDER_ALL = "__all__"
private const val FOLDER_NONE = "__none__"

/**
 * Экран 4. Хранилище: все записи, поиск (только в памяти после разблокировки —
 * открытого индекса на диске не существует), фильтры по категории/папке/избранному,
 * сортировка. Теги ищутся обычным поиском по слову тега.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    viewModel: VaultViewModel,
    navController: NavController,
    initialCategoryName: String?
) {
    val items by viewModel.items.collectAsState()
    val folders by viewModel.folders.collectAsState()

    var query by remember { mutableStateOf("") }
    var categoryFilter by remember(initialCategoryName) {
        mutableStateOf(initialCategoryName?.let { runCatching { ItemCategory.valueOf(it) }.getOrNull() })
    }
    var favoritesOnly by remember { mutableStateOf(false) }
    var folderFilter by remember { mutableStateOf(FOLDER_ALL) }
    var sortMode by remember { mutableStateOf(SortMode.UPDATED) }
    var showSortPicker by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }

    val folderName = { folderId: String? -> folders.firstOrNull { it.id == folderId }?.name }

    val filtered = remember(items, query, categoryFilter, favoritesOnly, folderFilter, sortMode) {
        val base = items.filter { item ->
            item.matchesQuery(query) &&
                (categoryFilter == null || item.category() == categoryFilter) &&
                (!favoritesOnly || item.favorite) &&
                when (folderFilter) {
                    FOLDER_ALL -> true
                    FOLDER_NONE -> item.folderId == null
                    else -> item.folderId == folderFilter
                }
        }
        when (sortMode) {
            SortMode.UPDATED -> base.sortedByDescending { it.updatedAt }
            SortMode.CREATED -> base.sortedByDescending { it.createdAt }
            SortMode.TITLE -> base.sortedBy { it.title.lowercase() }
            SortMode.FAVORITES -> base.sortedWith(
                compareByDescending<com.noxvault.app.domain.model.VaultItem> { it.favorite }
                    .thenByDescending { it.updatedAt }
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Row(
                modifier = Modifier.padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Хранилище", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Показано ${filtered.size} из ${items.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { viewModel.lockNow() }) {
                    Icon(Icons.Filled.Lock, contentDescription = "Заблокировать хранилище")
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Поиск: название, тег, логин…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = categoryFilter == null && !favoritesOnly,
                        onClick = { categoryFilter = null; favoritesOnly = false },
                        label = { Text("Все") }
                    )
                }
                item {
                    FilterChip(
                        selected = favoritesOnly,
                        onClick = { favoritesOnly = !favoritesOnly },
                        label = { Text("⭐ Избранное") }
                    )
                }
                items(ItemCategory.entries) { category ->
                    FilterChip(
                        selected = categoryFilter == category,
                        onClick = {
                            categoryFilter = if (categoryFilter == category) null else category
                        },
                        label = { Text(category.titleRu) }
                    )
                }
            }

            if (folders.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = folderFilter == FOLDER_ALL,
                            onClick = { folderFilter = FOLDER_ALL },
                            label = { Text("Все папки") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = folderFilter == FOLDER_NONE,
                            onClick = { folderFilter = FOLDER_NONE },
                            label = { Text("Без папки") }
                        )
                    }
                    items(folders) { folder ->
                        FilterChip(
                            selected = folderFilter == folder.id,
                            onClick = { folderFilter = folder.id },
                            label = { Text("📁 ${folder.name}") }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showSortPicker = true }) {
                    Icon(Icons.Filled.Sort, contentDescription = null)
                    Text("  ${sortMode.label}")
                }
            }
            Spacer(Modifier.height(8.dp))

            if (items.isEmpty()) {
                EmptyState(
                    Icons.Filled.Storage,
                    "Хранилище пусто",
                    "Нажмите «+» и создайте первую запись. Можно начать с самого важного пароля."
                )
            } else if (filtered.isEmpty()) {
                EmptyState(
                    Icons.Filled.Search,
                    "Ничего не найдено",
                    "Измените запрос или сбросьте фильтры кнопкой «Все»."
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filtered, key = { it.id }) { item ->
                        ItemCard(
                            item = item,
                            folderName = folderName(item.folderId),
                            onClick = { navController.navigate(Routes.detail(item.id)) },
                            onToggleFavorite = { viewModel.toggleFavorite(item) }
                        )
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }

        FloatingActionButton(
            onClick = { showCategoryPicker = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Добавить запись")
        }
    }

    if (showSortPicker) {
        OptionPickerDialog(
            title = "Сортировка",
            options = SortMode.entries.map { it.label },
            selectedIndex = SortMode.entries.indexOf(sortMode),
            onPick = { index ->
                sortMode = SortMode.entries[index]
                showSortPicker = false
            },
            onDismiss = { showSortPicker = false }
        )
    }

    if (showCategoryPicker) {
        CategoryPickerDialog(
            onPick = { category ->
                showCategoryPicker = false
                navController.navigate(Routes.edit(category = category.name))
            },
            onDismiss = { showCategoryPicker = false }
        )
    }
}
