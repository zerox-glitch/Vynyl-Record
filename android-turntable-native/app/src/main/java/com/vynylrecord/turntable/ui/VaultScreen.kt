package com.vynylrecord.turntable.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vynylrecord.turntable.model.RecordMetadata
import com.vynylrecord.turntable.studio.StudioUiState
import com.vynylrecord.turntable.vault.Press

/** Stable handles for the Vault's instrumentation tests. */
object VaultTestTags {
    const val SCENE = "vault_scene"
    const val LIST = "vault_list"
    const val EMPTY = "vault_empty"

    fun row(id: String): String = "vault_row_$id"

    fun play(id: String): String = "vault_play_$id"

    fun edit(id: String): String = "vault_edit_$id"

    fun delete(id: String): String = "vault_delete_$id"
}

/**
 * The Vault: every side that has been pressed, newest first.
 *
 * A row is the record itself — title, who it is for, how long it runs, and the sound of it — plus the
 * three things a person wants to do with it: put it on the deck, rename it, or let it go.
 */
@Composable
fun VaultScreen(
    state: StudioUiState,
    onPlay: (Press) -> Unit,
    onEdit: (Press) -> Unit,
    onDelete: (Press) -> Unit,
    onRename: (Press, RecordMetadata) -> Unit,
    onOpenStudio: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<Press?>(null) }
    var renaming by remember { mutableStateOf<Press?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VynylColors.Background)
            .testTag(VaultTestTags.SCENE),
    ) {
        ScreenHeader(
            title = "The Vault",
            subtitle = if (state.vault.isEmpty()) {
                "Nothing pressed yet"
            } else {
                "${state.vault.size} side${if (state.vault.size == 1) "" else "s"} · ${state.vaultSizeLabel}"
            },
        )

        StatusBar(
            notice = state.notice,
            error = state.error,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        if (state.vault.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                EmptyState(
                    icon = VynylIcon.VAULT,
                    title = "The shelf is empty",
                    body = "Every side you press lands here, with its label, its sound and its own " +
                        "audio file — playable offline, for as long as you keep it.",
                    modifier = Modifier.testTag(VaultTestTags.EMPTY),
                    action = {
                        VynylPrimaryButton(
                            label = "Press your first side",
                            onClick = onOpenStudio,
                            icon = VynylIcon.MIC,
                        )
                    },
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(VaultTestTags.LIST),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 8.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items = state.vault, key = { it.id }) { press ->
                VaultRow(
                    press = press,
                    onPlay = { onPlay(press) },
                    onEdit = { onEdit(press) },
                    onRename = { renaming = press },
                    onDelete = { pendingDelete = press },
                )
            }
        }
    }

    pendingDelete?.let { press ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Let this side go?", color = VynylColors.Cream) },
            text = {
                Text(
                    text = "\"${press.metadata.title}\" and its audio file will be deleted from this " +
                        "device. There is no cloud copy — nothing here has ever left the phone.",
                    color = VynylColors.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(press)
                    pendingDelete = null
                }) {
                    Text("Delete", color = VynylColors.Ruby)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Keep", color = VynylColors.Cream)
                }
            },
            containerColor = VynylColors.Panel,
            titleContentColor = VynylColors.Cream,
            textContentColor = VynylColors.Muted,
        )
    }

    renaming?.let { press ->
        RenameDialog(
            press = press,
            onDismiss = { renaming = null },
            onConfirm = { metadata ->
                onRename(press, metadata)
                renaming = null
            },
        )
    }
}

@Composable
private fun VaultRow(
    press: Press,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    BrassPanel(modifier = Modifier.testTag(VaultTestTags.row(press.id))) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = press.metadata.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = VynylColors.Cream,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = press.metadata.dedicationLine(),
                        style = MaterialTheme.typography.bodySmall,
                        color = VynylColors.AmberBright,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                VynylIconButton(
                    icon = VynylIcon.PLAY,
                    contentDescription = "Play ${press.metadata.title} on the deck",
                    onClick = onPlay,
                    size = 48.dp,
                    selected = true,
                    modifier = Modifier.testTag(VaultTestTags.play(press.id)),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(press.metadata.side.displayName)
                Pill(press.durationLabel)
                Pill(press.style.displayName)
                Pill(press.sizeLabel)
            }

            Text(
                text = press.recipe.describe(),
                style = MaterialTheme.typography.labelSmall,
                color = VynylColors.Muted,
            )
            Text(
                text = "${press.sourceLabel} · pressed ${press.metadata.date ?: "recently"}",
                style = MaterialTheme.typography.labelSmall,
                color = VynylColors.Muted.copy(alpha = 0.8f),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VynylSecondaryButton(
                    label = "Edit",
                    onClick = onEdit,
                    icon = VynylIcon.PENCIL,
                    modifier = Modifier.testTag(VaultTestTags.edit(press.id)),
                )
                VynylSecondaryButton(
                    label = "Rename",
                    onClick = onRename,
                    icon = VynylIcon.NOTE,
                )
                Spacer(modifier = Modifier.weight(1f))
                VynylIconButton(
                    icon = VynylIcon.TRASH,
                    contentDescription = "Delete ${press.metadata.title}",
                    onClick = onDelete,
                    size = 44.dp,
                    modifier = Modifier.testTag(VaultTestTags.delete(press.id)),
                )
            }
        }
    }
}

/** Renames a pressing: title, dedication and catalogue line only. */
@Composable
private fun RenameDialog(
    press: Press,
    onDismiss: () -> Unit,
    onConfirm: (RecordMetadata) -> Unit,
) {
    var title by remember { mutableStateOf(press.metadata.title) }
    var recipient by remember { mutableStateOf(press.metadata.recipient) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reprint the label", color = VynylColors.Cream) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "The audio is untouched — this only reprints what is on the label.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VynylColors.Muted,
                )
                VynylTextField(value = title, onValueChange = { title = it }, label = "Title")
                VynylTextField(value = recipient, onValueChange = { recipient = it }, label = "For")
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(press.metadata.copy(title = title, recipient = recipient))
                },
                enabled = title.isNotBlank() && recipient.isNotBlank(),
            ) {
                Text("Reprint", color = VynylColors.AmberBright)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = VynylColors.Cream) }
        },
        containerColor = VynylColors.Panel,
        titleContentColor = VynylColors.Cream,
        textContentColor = VynylColors.Muted,
    )
}

/** A compact now-playing strip shared by the Vault and the Deck. */
@Composable
fun NowPlayingCard(
    title: String,
    detail: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "$title. $detail"
                if (onClick != null) role = Role.Button
            },
        shape = RoundedCornerShape(14.dp),
        color = VynylColors.PanelRaised,
        border = BorderStroke(1.dp, VynylColors.Brass),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(VynylColors.Ruby, RoundedCornerShape(17.dp)),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = VynylColors.Cream,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = VynylColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trailing != null) trailing()
        }
    }
}
