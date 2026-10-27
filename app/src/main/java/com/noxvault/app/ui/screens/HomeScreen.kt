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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.noxvault.app.domain.model.Folder
import com.noxvault.app.domain.model.ItemCategory
import com.noxvault.app.ui.components.CategoryAvatar
import com.noxvault.app.ui.components.CategoryPickerDialog
import com.noxvault.app.ui.components.ConfirmDialog
import com.noxvault.app.ui.components.EmptyState
import com.noxvault.app.ui.components.ItemCard
import com.noxvault.app.ui.components.NoxCard
import com.noxvault.app.ui.components.SectionTitle
import com.noxvault.app.ui.navigation.Routes
import com.noxvault.app.viewmodel.VaultViewModel

/** Экран 3. Главная: поиск, статистика, категории, избранное, недавние, папки. */
@Composable
fun HomeScreen(viewModel: VaultViewModel, navController: NavController) {
    val items by viewModel.items.collectAsState()
    val folders by viewModel.folders.collectAsState()
    var query by remember { mutableStateOf("") }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var folderToDelete by remember { mutableStateOf<Folder?>(null) }

    val folderName = { folderId: String? -> folders.firstOrNull { it.id == folderId }?.name }
    val searching = query.isNotBlank()
    val searchResults = remember(items, query) {
        if (!searching) emptyList()
        else items.filter { it.matchesQuery(query) }.sortedByDescending { it.updatedAt }
    }
    val favorites = remember(items) { items.filter { it.favorite }.sortedBy { it.title.lowercase() } }
    val recent = remember(items) {
        items.filter { it.lastOpenedAt > 0 }.sortedByDescending { it.lastOpenedAt }.take(5)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.Top
        ) {
            item {
                Row(
                    modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("NOX Vault", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Записей: ${items.size} · всё зашифровано и хранится локально",
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
                    placeholder = { Text("Поиск по хранилищу…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (searching) {
                item {
                    SectionTitle("Найдено: ${searchResults.size}")
                }
                if (searchResults.isEmpty()) {
                    item {
                        EmptyState(
                            Icons.Filled.Search,
                            "Ничего не найдено",
                            "Попробуйте другое слово. Поиск идёт по названиям, тегам и значениям полей — только в памяти, после разблокировки."
                        )
                    }
                } else {
                    items(searchResults, key = { it.id }) { item ->
                        ItemCard(
                            item = item,
                            folderName = folderName(item.folderId),
                            onClick = { navController.navigate(Routes.detail(item.id)) },
                            onToggleFavorite = { viewModel.toggleFavorite(item) }
                        )
                    }
                }
            } else {
                item {
                    Spacer(Modifier.height(16.dp))
                    NoxCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            StatCell("Всего записей", items.size)
                            StatCell("Избранных", favorites.size)
                            StatCell("Папок", folders.size)
                        }
                    }
                    SectionTitle("Категории")
                }
                // Сетка категорий по 2 в ряд
                items(ItemCategory.entries.chunked(2)) { rowCategories ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowCategories.forEach { category ->
                            val count = items.count { it.category() == category }
                            NoxCard(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 5.dp),
                                onClick = { navController.navigate(Routes.vault(category.name)) }
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    CategoryAvatar(category, size = 42)
                                    Spacer(Modifier.height(10.dp))
                                    Text(category.titleRu, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                                    Text(
                                        "Записей: $count",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (rowCategories.size == 1) Spacer(Modifier.weight(1f))
                    }
                }

                if (items.isEmpty()) {
                    item {
                        EmptyState(
                            Icons.Filled.Shield,
                            "Хранилище пока пусто",
                            "Нажмите «+» внизу справа и добавьте первый пароль, ключ или заметку. Всё, что вы сохраните, будет зашифровано AES-256."
                        )
                    }
                }

                if (favorites.isNotEmpty()) {
                    item { SectionTitle("⭐ Избранное") }
                    items(favorites, key = { "fav-" + it.id }) { item ->
                        ItemCard(
                            item = item,
                            folderName = folderName(item.folderId),
                            onClick = { navController.navigate(Routes.detail(item.id)) },
                            onToggleFavorite = { viewModel.toggleFavorite(item) }
                        )
                    }
                }

                if (recent.isNotEmpty()) {
                    item { SectionTitle("Недавно открытые") }
                    items(recent, key = { "recent-" + it.id }) { item ->
                        ItemCard(
                            item = item,
                            folderName = folderName(item.folderId),
                            onClick = { navController.navigate(Routes.detail(item.id)) },
                            onToggleFavorite = { viewModel.toggleFavorite(item) }
                        )
                    }
                }

                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Папки", modifier = Modifier.weight(1f))
                        IconButton(onClick = { showNewFolder = true }) {
                            Icon(Icons.Filled.CreateNewFolder, contentDescription = "Создать папку")
                        }
                    }
                    if (folders.isEmpty()) {
                        Text(
                            "Папок пока нет. Создайте папку, чтобы группировать записи: например «Работа», «Дом», «Финансы». Фильтр по папкам — на вкладке «Хранилище».",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                    }
                }
                items(folders, key = { "folder-" + it.id }) { folder ->
                    NoxCard(modifier = Modifier.padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📁", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(folder.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Записей: ${items.count { it.folderId == folder.id }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { folderToDelete = folder }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Удалить папку")
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }

        FloatingActionButton(
            onClick = { showCategoryPicker = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Добавить запись")
        }
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

    if (showNewFolder) {
        NewFolderDialog(
            onCreate = { name ->
                viewModel.addFolder(name)
                showNewFolder = false
            },
            onDismiss = { showNewFolder = false }
        )
    }

    folderToDelete?.let { folder ->
        ConfirmDialog(
            title = "Удалить папку «${folder.name}»?",
            text = "Записи внутри не удалятся — они просто перестанут принадлежать папке.",
            confirmText = "Удалить папку",
            onConfirm = {
                viewModel.deleteFolder(folder.id)
                folderToDelete = null
            },
            onDismiss = { folderToDelete = null }
        )
    }
}

@Composable
private fun StatCell(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun NewFolderDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая папка") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Название папки") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
