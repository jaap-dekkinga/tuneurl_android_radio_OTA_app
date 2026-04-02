package com.tuneurlradio.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.tuneurlradio.app.R


@Composable
fun ListeningControlButton(
    isListening: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.padding(8.dp)
        ) {
            Icon(
                painter = if (isListening) {
                    painterResource(id = R.drawable.ic_ota_listening)
                } else {
                    painterResource(id = R.drawable.ic_speaker_zzz)
                },
                contentDescription = if (isListening) "Stop Listening" else "Start Listening",
                tint = Color.Unspecified, // Uses colors defined in XML (Black)
                modifier = Modifier.size(24.dp)
            )
        }
    }
}


