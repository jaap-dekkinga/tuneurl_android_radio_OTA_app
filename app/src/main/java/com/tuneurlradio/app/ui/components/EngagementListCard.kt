package com.tuneurlradio.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tuneurlradio.app.R
import com.tuneurlradio.app.domain.model.Station
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EngagementListCard(
    info: String?,
    infoUrl: String?,
    station: Station?,
    date: Date?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp)
    ) {
        Box(
            modifier = Modifier
                .width(100.dp)
                .fillMaxHeight()
                .clip(
                    RoundedCornerShape(
                        topStart = 8.dp,
                        bottomStart = 8.dp,
                        topEnd = 0.dp,
                        bottomEnd = 0.dp
                    )
                )
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            if (station?.imageURL != null) {
                AsyncImage(
                    model = station.imageURL,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(80.dp)
                        .padding(8.dp)
                )
            } else {
                Image(
                    painter = painterResource(id = R.drawable.station_logo),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(80.dp)
                        .padding(12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(vertical = 6.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = info?.takeIf { it.isNotBlank() } ?: "No Info",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            if (station != null) {
                Spacer(modifier = Modifier.height(4.dp))
                SourceInfo(station = station)
            }

            if (date != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatDate(date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SourceInfo(station: Station) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            if (station.imageURL != null) {
                AsyncImage(
                    model = station.imageURL,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Image(
                    painter = painterResource(id = R.drawable.station_logo),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(20.dp)
                        .padding(2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = station.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun formatDate(date: Date): String {
    val formatter = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
    return formatter.format(date)
}
