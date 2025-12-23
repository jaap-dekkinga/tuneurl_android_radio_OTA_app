package com.tuneurlradio.app.ui.screens.player

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tuneurlradio.app.R
import com.tuneurlradio.app.domain.model.PlayerState
import com.tuneurlradio.app.domain.model.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun PlayerScreen(
    station: Station?,
    isPlaying: Boolean,
    playerState: PlayerState,
    trackName: String?,
    artistName: String?,
    volume: Float,
    sleepTimerEndTime: Long?,
    onVolumeChange: (Float) -> Unit,
    onPlayPauseClick: () -> Unit,
    onShareClick: () -> Unit,
    onSleepTimerSet: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val artworkScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.8f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 300f),
        label = "artwork_scale"
    )

    Box(modifier = modifier.fillMaxSize()) {
        PlayerBackground(imageUrl = station?.imageURL)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(52.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.5f))
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            ArtworkView(
                imageUrl = station?.imageURL,
                modifier = Modifier.scale(artworkScale)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = station?.name ?: "-",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (trackName != null || artistName != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start
                ) {
                    trackName?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1
                        )
                    }
                    artistName?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(32.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))

            PlayerStatusLine(state = playerState)

            Spacer(modifier = Modifier.height(52.dp))

            IconButton(
                onClick = onPlayPauseClick,
                modifier = Modifier
                    .size(72.dp)
                    .background(Color.White.copy(alpha = 0.2f), CircleShape)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Stop" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(52.dp))

            VolumeSlider(
                volume = volume,
                onVolumeChange = onVolumeChange
            )

            Spacer(modifier = Modifier.height(32.dp))

            ControlButtons(
                onShareClick = onShareClick,
                socialUrl = station?.socialURL,
                sleepTimerEndTime = sleepTimerEndTime,
                onSleepTimerSet = onSleepTimerSet
            )

            station?.socialURL?.let { url ->
                Spacer(modifier = Modifier.height(24.dp))
                SocialsSection(socialUrl = url)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SocialsSection(socialUrl: String) {
    val context = LocalContext.current
    var linkMetadata by remember { mutableStateOf<LinkMetadata?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    
    LaunchedEffect(socialUrl) {
        isLoading = true
        linkMetadata = fetchLinkMetadata(socialUrl)
        isLoading = false
    }
    
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "Explore Our Socials",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clickable {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(socialUrl))
                        context.startActivity(intent)
                    } catch (e: Exception) {}
                },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = linkMetadata?.themeColor ?: Color(0xFFFF6B35)
            )
        ) {
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (linkMetadata?.imageUrl != null) {
                        AsyncImage(
                            model = linkMetadata?.imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInBrowser,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(linkMetadata?.themeColor ?: Color(0xFFFF6B35))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = linkMetadata?.title ?: linkMetadata?.siteName ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            maxLines = 2
                        )
                        if (linkMetadata?.description != null) {
                            Text(
                                text = linkMetadata?.description ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f),
                                maxLines = 1
                            )
                        }
                        Text(
                            text = Uri.parse(socialUrl).host ?: socialUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

private data class LinkMetadata(
    val title: String?,
    val description: String?,
    val imageUrl: String?,
    val siteName: String?,
    val themeColor: Color?
)

private suspend fun fetchLinkMetadata(url: String): LinkMetadata? {
    return withContext(Dispatchers.IO) {
        try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            val html = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            
            val title = extractMetaContent(html, "og:title") 
                ?: extractMetaContent(html, "twitter:title")
                ?: extractTitle(html)
            val description = extractMetaContent(html, "og:description")
                ?: extractMetaContent(html, "twitter:description")
                ?: extractMetaContent(html, "description")
            val imageUrl = extractMetaContent(html, "og:image")
                ?: extractMetaContent(html, "twitter:image")
            val siteName = extractMetaContent(html, "og:site_name")
            val themeColorHex = extractMetaContent(html, "theme-color")
            
            val themeColor = themeColorHex?.let { parseColor(it) }
            
            LinkMetadata(title, description, imageUrl, siteName, themeColor)
        } catch (e: Exception) {
            null
        }
    }
}

private fun extractMetaContent(html: String, property: String): String? {
    val patterns = listOf(
        """<meta[^>]*property=["']$property["'][^>]*content=["']([^"']+)["']""",
        """<meta[^>]*content=["']([^"']+)["'][^>]*property=["']$property["']""",
        """<meta[^>]*name=["']$property["'][^>]*content=["']([^"']+)["']""",
        """<meta[^>]*content=["']([^"']+)["'][^>]*name=["']$property["']"""
    )
    
    for (pattern in patterns) {
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
        val match = regex.find(html)
        if (match != null) {
            return match.groupValues[1]
        }
    }
    return null
}

private fun extractTitle(html: String): String? {
    val regex = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
    return regex.find(html)?.groupValues?.get(1)?.trim()
}

private fun parseColor(colorString: String): Color? {
    return try {
        val hex = colorString.trim().removePrefix("#")
        when (hex.length) {
            6 -> Color(android.graphics.Color.parseColor("#$hex"))
            3 -> {
                val expanded = hex.map { "$it$it" }.joinToString("")
                Color(android.graphics.Color.parseColor("#$expanded"))
            }
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun PlayerBackground(imageUrl: String?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF555555))
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(50.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.3f),
                                Color.Black.copy(alpha = 0.5f),
                                Color.Black.copy(alpha = 0.6f)
                            )
                        )
                    )
            )
        }
    }
}

@Composable
private fun ArtworkView(
    imageUrl: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(260.dp)
            .shadow(24.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black.copy(alpha = 0.3f))
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            )
        } else {
            Image(
                painter = painterResource(id = R.drawable.station_logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp)
            )
        }
    }
}

@Composable
private fun PlayerStatusLine(state: PlayerState) {
    val color = when (state) {
        PlayerState.LIVE -> Color(0xFF34C759)
        PlayerState.LOADING -> Color(0xFFFFCC00)
        PlayerState.OFFLINE -> Color.Gray
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = state.title,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun VolumeSlider(
    volume: Float,
    onVolumeChange: (Float) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = Icons.Default.VolumeDown,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp)
        )
        Slider(
            value = volume,
            onValueChange = onVolumeChange,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
            )
        )
        Icon(
            imageVector = Icons.Default.VolumeUp,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun ControlButtons(
    onShareClick: () -> Unit,
    socialUrl: String?,
    sleepTimerEndTime: Long?,
    onSleepTimerSet: (Int?) -> Unit
) {
    var showSleepMenu by remember { mutableStateOf(false) }
    val sleepTimerActive = sleepTimerEndTime != null
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White.copy(alpha = 0.15f))
                        .clickable { showSleepMenu = true }
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = if (sleepTimerActive) Icons.Filled.Timer else Icons.Default.Timer,
                        contentDescription = "Sleep Timer",
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Sleep",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                }
                
                if (sleepTimerEndTime != null) {
                    SleepTimerCountdown(endTime = sleepTimerEndTime)
                }
            }
            
            DropdownMenu(
                expanded = showSleepMenu,
                onDismissRequest = { showSleepMenu = false },
                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
            ) {
                DropdownMenuItem(
                    text = { Text("2 hours") },
                    onClick = {
                        onSleepTimerSet(2 * 60 * 60)
                        showSleepMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("1 hour") },
                    onClick = {
                        onSleepTimerSet(60 * 60)
                        showSleepMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("30 mins") },
                    onClick = {
                        onSleepTimerSet(30 * 60)
                        showSleepMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("15 mins") },
                    onClick = {
                        onSleepTimerSet(15 * 60)
                        showSleepMenu = false
                    }
                )
                if (com.tuneurlradio.app.BuildConfig.DEBUG) {
                    DropdownMenuItem(
                        text = { Text("1 min") },
                        onClick = {
                            onSleepTimerSet(60)
                            showSleepMenu = false
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("None") },
                    onClick = {
                        onSleepTimerSet(null)
                        showSleepMenu = false
                    }
                )
            }
        }

        IconButton(onClick = { }) {
            Icon(
                imageVector = Icons.Default.Cast,
                contentDescription = "Cast",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }

        if (socialUrl != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.15f))
                    .clickable(onClick = onShareClick)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share",
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Share",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
            }
        } else {
            Spacer(modifier = Modifier.width(48.dp))
        }
    }
}

@Composable
private fun SleepTimerCountdown(endTime: Long) {
    var remainingTime by remember { mutableStateOf(endTime - System.currentTimeMillis()) }
    
    LaunchedEffect(endTime) {
        while (remainingTime > 0) {
            delay(1000)
            remainingTime = endTime - System.currentTimeMillis()
        }
    }
    
    if (remainingTime > 0) {
        val hours = (remainingTime / (1000 * 60 * 60)).toInt()
        val minutes = ((remainingTime / (1000 * 60)) % 60).toInt()
        
        val timeText = when {
            hours > 0 -> "Sleep in: ${hours}h ${minutes}m"
            minutes > 0 -> "Sleep in: ${minutes}m"
            else -> "Sleep in: <1m"
        }
        
        Text(
            text = timeText,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
