package tech.miyukivigil.totem.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.miyukivigil.totem.core.Account
import tech.miyukivigil.totem.core.BrandIcons
import tech.miyukivigil.totem.core.LetterBadge

/** The bundled logos; null while they load (badges show letters meanwhile). */
val LocalBrandIcons = compositionLocalOf<BrandIcons?> { null }

private val vectors = HashMap<String, ImageVector>()

private fun BrandIcons.Icon.vector(tint: Color): ImageVector = synchronized(vectors) {
    vectors.getOrPut("$slug/${tint.value}") {
        ImageVector.Builder(slug, 24.dp, 24.dp, 24f, 24f)
            .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(tint))
            .build()
    }
}

private fun hexColor(hex: String) = Color(0xFF000000 or hex.toLong(16))

/** A round badge: the brand's logo on its color, or the first letter on a color of its own. */
@Composable
fun AccountIcon(account: Account, size: Dp = 40.dp) {
    val icon = LocalBrandIcons.current?.forAccount(account)
    BrandBadge(icon, LetterBadge.letter(account), LetterBadge.color(account), size)
}

@Composable
private fun BrandBadge(icon: BrandIcons.Icon?, letter: String, letterHex: String, size: Dp) {
    val bg = hexColor(icon?.hex ?: letterHex)
    val fg = if (bg.luminance() > 0.6f) Color.Black else Color.White
    // The faint ring keeps black logos (GitHub, X, ...) visible on a dark background.
    Box(Modifier.size(size).clip(CircleShape).background(bg).border(1.dp, Color.Gray.copy(alpha = 0.35f), CircleShape), contentAlignment = Alignment.Center) {
        if (icon != null) {
            Image(icon.vector(fg), contentDescription = icon.title, modifier = Modifier.size(size * 0.55f))
        } else {
            Text(letter, color = fg, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.45f).sp)
        }
    }
}

/**
 * Picks [Account.icon]: null = automatic (from the service name), "" = no logo, or a slug.
 */
@Composable
fun IconPickerDialog(account: Account, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val icons = LocalBrandIcons.current
    var query by remember { mutableStateOf(account.issuer) }
    val results = remember(query, icons) { icons?.search(query) ?: emptyList() }
    val auto = icons?.match(account.issuer)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose an icon") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Search brands") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item {
                        PickerRow(auto, "Automatic" + (auto?.let { " (${it.title})" } ?: " (letter)"), account) { onPick(null) }
                    }
                    item { PickerRow(null, "No logo, just the letter", account) { onPick("") } }
                    items(results, key = { it.slug }) { icon -> PickerRow(icon, icon.title, account) { onPick(icon.slug) } }
                    if (icons != null && results.isEmpty() && query.isNotBlank()) {
                        item { Text("No brand called \"$query\". Logos come from Simple Icons; some companies aren't in it.", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PickerRow(icon: BrandIcons.Icon?, title: String, account: Account, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BrandBadge(icon, LetterBadge.letter(account), LetterBadge.color(account), 32.dp)
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}
