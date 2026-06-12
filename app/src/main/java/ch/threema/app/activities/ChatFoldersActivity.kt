package ch.threema.app.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import ch.threema.android.showToast
import ch.threema.app.R
import ch.threema.app.compose.common.SpacerVertical
import ch.threema.app.compose.common.ThemedText
import ch.threema.app.compose.theme.ThreemaTheme
import ch.threema.app.compose.theme.dimens.GridUnit
import ch.threema.app.dialogs.GenericAlertDialog
import ch.threema.app.dialogs.GenericAlertDialog.DialogClickListener
import ch.threema.app.services.ChatFolderService
import ch.threema.app.ui.InsetSides
import ch.threema.app.ui.applyDeviceInsetsAsPadding
import ch.threema.storage.models.ChatFolderModel
import org.koin.android.ext.android.inject

/**
 * Screen for managing the local chat folders: create, rename, delete.
 */
class ChatFoldersActivity : ThreemaToolbarActivity(), DialogClickListener {

    private val chatFolderService: ChatFolderService by inject()

    private var foldersState by mutableStateOf<List<ChatFolderModel>>(emptyList())

    @LayoutRes
    override fun getLayoutResource(): Int = R.layout.activity_chat_folders

    override fun initActivity(savedInstanceState: Bundle?): Boolean {
        if (!super.initActivity(savedInstanceState)) {
            return false
        }

        appBarLayout?.applyDeviceInsetsAsPadding(
            insetSides = InsetSides.ltr(),
        )

        val toolbar = findViewById<MaterialToolbar>(R.id.material_toolbar)
        toolbar.setNavigationOnClickListener { _ -> finish() }
        toolbar.setTitle(R.string.chat_folders)

        reloadFolders()

        findViewById<ComposeView>(R.id.chat_folders_list).setContent {
            ThreemaTheme {
                Scaffold(
                    contentWindowInsets = WindowInsets
                        .safeDrawing
                        .only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                        ),
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    floatingActionButton = {
                        FloatingActionButton(
                            onClick = ::showCreateFolderDialog,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = stringResource(R.string.create_folder),
                            )
                        }
                    },
                ) { insetsPadding ->
                    ChatFoldersContent(
                        insetsPadding = insetsPadding,
                        folders = foldersState,
                        onClickRename = ::showRenameFolderDialog,
                        onClickDelete = ::showDeleteFolderDialog,
                    )
                }
            }
        }

        return true
    }

    private fun reloadFolders() {
        foldersState = chatFolderService.getFolders()
    }

    private fun showCreateFolderDialog() {
        showFolderNameDialog(
            titleRes = R.string.create_folder,
            initialName = "",
        ) { name ->
            chatFolderService.createFolder(name)
            reloadFolders()
        }
    }

    private fun showRenameFolderDialog(folder: ChatFolderModel) {
        showFolderNameDialog(
            titleRes = R.string.rename_folder,
            initialName = folder.name,
        ) { name ->
            chatFolderService.renameFolder(folder.id, name)
            reloadFolders()
        }
    }

    private fun showFolderNameDialog(
        titleRes: Int,
        initialName: String,
        onConfirm: (String) -> Unit,
    ) {
        val textInputLayout = TextInputLayout(this).apply {
            hint = getString(R.string.folder_name_hint)
        }
        val editText = TextInputEditText(textInputLayout.context).apply {
            setText(initialName)
            setSelection(text?.length ?: 0)
        }
        textInputLayout.addView(editText)

        val container = FrameLayout(this).apply {
            val horizontalPadding = resources.getDimensionPixelSize(R.dimen.edittext_padding)
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            addView(textInputLayout)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = editText.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    showToast(R.string.folder_name_empty)
                } else {
                    onConfirm(name)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteFolderDialog(folder: ChatFolderModel) {
        val dialog = GenericAlertDialog.newInstance(
            R.string.delete_folder,
            R.string.delete_folder_confirm,
            R.string.ok,
            R.string.cancel,
        )
        dialog.setData(folder.id)
        dialog.show(supportFragmentManager, DIALOG_TAG_DELETE_FOLDER)
    }

    override fun onYes(tag: String?, data: Any?) {
        if (tag == DIALOG_TAG_DELETE_FOLDER) {
            (data as? Long)?.let { folderId ->
                chatFolderService.deleteFolder(folderId)
                reloadFolders()
            }
        }
    }

    companion object {
        private const val DIALOG_TAG_DELETE_FOLDER = "delFolder"

        fun createIntent(context: Context): Intent =
            Intent(context, ChatFoldersActivity::class.java)
    }
}

@Composable
private fun ChatFoldersContent(
    insetsPadding: PaddingValues,
    folders: List<ChatFolderModel>,
    onClickRename: (ChatFolderModel) -> Unit,
    onClickDelete: (ChatFolderModel) -> Unit,
) {
    if (folders.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insetsPadding)
                .padding(horizontal = GridUnit.x2),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                modifier = Modifier.size(GridUnit.x7),
                painter = painterResource(R.drawable.ic_folder_outline),
                contentDescription = null,
                tint = LocalContentColor.current,
            )
            SpacerVertical(GridUnit.x2)
            ThemedText(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.no_chat_folders),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(insetsPadding),
        ) {
            items(
                items = folders,
                key = { folder -> folder.id },
            ) { folder ->
                ChatFolderRow(
                    folder = folder,
                    onClickRename = onClickRename,
                    onClickDelete = onClickDelete,
                )
            }
        }
    }
}

@Composable
private fun ChatFolderRow(
    folder: ChatFolderModel,
    onClickRename: (ChatFolderModel) -> Unit,
    onClickDelete: (ChatFolderModel) -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = GridUnit.x2,
                end = GridUnit.x1,
            )
            .padding(vertical = GridUnit.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            modifier = Modifier.size(GridUnit.x3),
            painter = painterResource(R.drawable.ic_folder_outline),
            contentDescription = null,
            tint = LocalContentColor.current,
        )
        SpacerHorizontal()
        ThemedText(
            modifier = Modifier
                .weight(1f),
            text = folder.name,
            style = MaterialTheme.typography.bodyLarge,
        )
        IconButton(onClick = { onClickRename(folder) }) {
            Icon(
                painter = painterResource(R.drawable.ic_pencil_outline),
                contentDescription = stringResource(R.string.rename_folder),
            )
        }
        IconButton(onClick = { onClickDelete(folder) }) {
            Icon(
                painter = painterResource(R.drawable.ic_delete_outline),
                contentDescription = stringResource(R.string.delete_folder),
            )
        }
    }
}

@Composable
private fun SpacerHorizontal() {
    androidx.compose.foundation.layout.Spacer(
        modifier = Modifier.size(GridUnit.x2),
    )
}
