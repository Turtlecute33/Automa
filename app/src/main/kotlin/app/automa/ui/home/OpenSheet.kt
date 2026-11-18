package app.automa.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.automa.env.Agent
import app.automa.git.Repo
import app.automa.ui.Icons
import app.automa.ui.SectionHeader

/**
 * Opens a repository (DESIGN §13): a tap on an agent opens a chat, its terminal button opens the agent's own terminal
 * UI. Also a shell and Delete.
 */
@Composable
fun OpenSheet(
    repo: Repo,
    agents: List<Agent>,
    onDismiss: () -> Unit,
    onChat: (Agent) -> Unit,
    onTerminal: (Agent?) -> Unit,
    onDelete: () -> Unit,
) {
    val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(repo.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        repo.branch?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
        }
        SectionHeader("Chat")
        agents.forEach { agent ->
            ListItem(
                headlineContent = { Text(agent.label) },
                leadingContent = { Icon(Icons.Robot, null) },
                trailingContent = { IconButton(onClick = { onTerminal(agent) }) { Icon(Icons.Terminal, "${agent.label} in the terminal") } },
                colors = clear,
                modifier = Modifier.clickable { onChat(agent) },
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        ListItem(
            headlineContent = { Text("Shell") },
            leadingContent = { Icon(Icons.Terminal, null) },
            colors = clear,
            modifier = Modifier.clickable { onTerminal(null) },
        )
        ListItem(
            headlineContent = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            leadingContent = { Icon(Icons.Delete, null, tint = MaterialTheme.colorScheme.error) },
            colors = clear,
            modifier = Modifier.clickable(onClick = onDelete),
        )
        Spacer(Modifier.height(24.dp))
    }
}
