package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.ExtractedMedia
import com.example.data.model.FileCategory
import com.example.ui.theme.AccentGreen
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DarkCardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.DownloadViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnifferSheet(
    items: List<ExtractedMedia>,
    onToggleItem: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onDownloadSelected: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val selectedCount = items.count { it.isSelected }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface,
        scrimColor = Color.Black.copy(alpha = 0.65f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Bulunan Medya ve Dosyalar",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "${items.size} öğe tespit edildi (${selectedCount} seçili)",
                        fontSize = 13.sp,
                        color = CyberCyan
                    )
                }

                Row {
                    TextButton(
                        onClick = { onSelectAll(selectedCount != items.size) },
                        modifier = Modifier.testTag("toggle_all_sniffer_button")
                    ) {
                        Text(
                            text = if (selectedCount == items.size) "Seçimi Kaldır" else "Tümünü Seç",
                            color = ElectricBlue,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Items list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .height((items.size * 76).coerceAtMost(380).dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.url }) { media ->
                    SnifferItemRow(
                        media = media,
                        onToggle = { onToggleItem(media.url) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bottom download button
            Button(
                onClick = onDownloadSelected,
                enabled = selectedCount > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("download_selected_sniffer_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberCyan,
                    contentColor = Color(0xFF0A0F1D),
                    disabledContainerColor = DarkSurfaceVariant,
                    disabledContentColor = TextMuted
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "İndir",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Seçilenleri İndir ($selectedCount Öğe)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun SnifferItemRow(
    media: ExtractedMedia,
    onToggle: () -> Unit
) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        color = DarkSurfaceVariant,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (media.isSelected) CyberCyan.copy(alpha = 0.6f) else DarkCardBorder
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection icon
            IconButton(
                onClick = onToggle,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (media.isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (media.isSelected) "Seçili" else "Seçilmedi",
                    tint = if (media.isSelected) CyberCyan else TextMuted,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Thumbnail / Icon
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F172A))
                    .border(0.5.dp, DarkCardBorder, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (media.thumbnailUrl != null) {
                    AsyncImage(
                        model = media.thumbnailUrl,
                        contentDescription = media.title,
                        modifier = Modifier.size(46.dp),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    val icon = when (media.category) {
                        FileCategory.APKS -> Icons.Default.Bolt
                        FileCategory.MEDIA -> Icons.Default.Movie
                        FileCategory.ARCHIVES -> Icons.Default.Folder
                        FileCategory.DOCUMENTS -> Icons.Default.Description
                        else -> Icons.Default.Description
                    }
                    val iconColor = when (media.category) {
                        FileCategory.APKS -> AccentGreen
                        FileCategory.MEDIA -> CyberCyan
                        FileCategory.ARCHIVES -> Color(0xFFA855F7)
                        FileCategory.DOCUMENTS -> ElectricBlue
                        else -> Color(0xFFA855F7)
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = media.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = media.category.label,
                        fontSize = 11.sp,
                        color = CyberCyan,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (media.approxSize > 0) {
                        Text(
                            text = " • ${DownloadViewModel.formatBytes(media.approxSize)}",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}
