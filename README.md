# TuneURL Radio App - Android

A modern Android radio streaming application with TuneURL audio fingerprinting technology for interactive audio engagement detection.

## Tech Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose with Material 3
- **Architecture**: MVI (Model-View-Intent)
- **Dependency Injection**: Hilt
- **Database**: Room
- **Preferences**: DataStore
- **Networking**: Retrofit, OkHttp
- **Image Loading**: Coil
- **Audio Playback**: Media3 ExoPlayer
- **Audio Fingerprinting**: TuneURL SDK (Native C++ via JNI)
- **Async**: Kotlin Coroutines & Flow
- **Navigation**: Jetpack Navigation Compose
- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 35

---

## Features

### 1. Radio Station Streaming

**Technical Implementation:**

- `RadioPlaybackService` - MediaSessionService for background audio playback
- `RadioPlayerManager` - Manages MediaController connection and playback state
- `ExoPlayer` with custom `CapturingRenderersFactory` for audio stream interception
- `StreamAudioCapture` - Custom AudioSink wrapper to capture decoded PCM audio
- ICY metadata parsing for track/artist information
- Background playback with media notification controls

**Components:**

- `StationsScreen` - 2-column LazyVerticalGrid displaying radio stations
- `PlayerScreen` - Full-screen player with blurred background, volume slider, share button
- `MiniPlayerView` - Compact player bar with play/pause and station info

### 2. TuneURL Audio Fingerprinting & Triggering

**Technical Implementation:**

- `TuneURLDetector` - Core detection engine running continuous fingerprint extraction
- `StreamDataCapture` - Downloads raw MP3 stream data via OkHttp for fingerprinting
- `NativeResampler` - JNI bridge to native C++ resampler (10,240 Hz target)
- `TuneURLSDK.extractFingerprintFromBuffer()` - Native fingerprint extraction
- `APIService` - SDK service for fingerprint search API calls
- BroadcastReceiver pattern for async API result handling

**Detection Flow:**

1. Stream URL captured via HTTP (OkHttp)
2. MP3 data buffered (250KB rolling buffer)
3. Every 10 seconds: MP3 decoded to PCM via MediaCodec
4. Stereo to mono conversion
5. Resampled to 10,240 Hz via native resampler
6. Fingerprint extracted via TuneURL SDK
7. Fingerprint sent to search API
8. Match results received via broadcast
9. EngagementSheet displayed on valid match (≥25% match percentage)

**API Endpoints:**

- Search Fingerprint: `https://pnz3vadc52.execute-api.us-east-2.amazonaws.com/dev/search-fingerprint`
- Interests: `https://65neejq3c9.execute-api.us-east-2.amazonaws.com/interests`
- Poll API: `http://pollapiwebservice.us-east-2.elasticbeanstalk.com/api/pollapi`
- CYOA: `https://pnz3vadc52.execute-api.us-east-2.amazonaws.com/dev/get-cyoa-mp3`

### 3. Engagement Sheet (Interest Sheet)

**Technical Implementation:**

- `EngagementSheet` - ModalBottomSheet composable
- WebView integration for `open_page` and `save_page` engagement types
- Coil AsyncImage for coupon image display
- Auto-dismiss after 15 seconds
- Action handling: Save, Call, SMS based on engagement type

**Supported Engagement Types:**

- `open_page` - Opens URL in embedded WebView
- `save_page` - Saves bookmark
- `coupon` - Displays coupon image
- `phone` - Initiates phone call
- `sms` - Opens SMS composer
- `poll` - Interactive polling

### 4. News Feed

**Technical Implementation:**

- `RssFeedParser` - XML parsing using XmlPullParser
- `NewsViewModel` - MVI ViewModel with loading/error states
- `NewsScreen` - LazyColumn with news article cards
- External browser intent for article viewing

**Data Source:**

- RSS Feed: `https://www.billboard.com/c/music/music-news/feed/`

### 5. Saved Engagements

**Technical Implementation:**

- `EngagementsRepository` - Repository pattern for data access
- `EngagementDao` - Room DAO for CRUD operations
- `SavedEngagementsViewModel` - MVI ViewModel
- `SavedEngagementsScreen` - LazyColumn with swipe-to-delete

**Database Schema:**

```kotlin
@Entity(tableName = "engagements")
data class EngagementEntity(
    @PrimaryKey val id: Int,
    val rawType: String,
    val name: String,
    val description: String,
    val info: String,
    val heardAt: Long,
    val sourceStationId: Int?,
    val isSaved: Boolean
)
```

### 6. Turls History

**Technical Implementation:**

- `TurlsHistoryViewModel` - Loads engagement history from Room
- `TurlsHistoryScreen` - Displays all heard engagements
- Filtering by saved vs all engagements

### 7. Settings

**Technical Implementation:**

- `SettingsDataStore` - Preferences DataStore for app settings
- `SettingsViewModel` - MVI ViewModel for settings state
- `SettingsScreen` - Material 3 settings UI

**Configurable Options:**

- Store History (Boolean)
- Engagement Display Mode (Sheet/Notification)
- Parsing Settings (Continuous/Trigger mode)
- App version display

### 8. Navigation

**Technical Implementation:**

- `AppNavigation` - NavHost with route definitions
- `AppTab` - Enum defining bottom navigation tabs
- `MainScreen` - Scaffold with NavigationBar

**Tabs:**

1. News - Newspaper icon
2. Stations - Radio icon
3. Saved Turls - Bookmark icon
4. Turls - ViewTimeline icon
5. Settings - Settings icon

### 9. Listening Control

**Technical Implementation:**

- `ListeningControlButton` - Floating action button for TuneURL detection toggle
- `TuneURLManager` - Singleton managing detection lifecycle
- Visual feedback: Mic icon (listening) / Mic Off icon (not listening)

---

## Architecture

### MVI Pattern

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│    View     │────▶│   Intent    │────▶│  ViewModel  │
│  (Compose)  │     │   (Sealed)  │     │   (Hilt)    │
└─────────────┘     └─────────────┘     └──────┬──────┘
       ▲                                       │
       │            ┌─────────────┐            │
       └────────────│    State    │◀───────────┘
                    │  (Data Class)│
                    └─────────────┘
```

### Base Classes

```kotlin
abstract class MviViewModel<State, Intent, Effect>(
    initialState: State
) : ViewModel() {
    protected val _state = MutableStateFlow(initialState)
    val state: StateFlow<State> = _state.asStateFlow()

    abstract fun handleIntent(intent: Intent)
}
```

---

## Project Structure

```
app/src/main/java/com/tuneurlradio/app/
├── core/
│   └── mvi/
│       └── MviViewModel.kt
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt
│   │   ├── EngagementDao.kt
│   │   ├── SettingsDataStore.kt
│   │   └── StationsDataSource.kt
│   ├── remote/
│   │   └── RssFeedParser.kt
│   └── repository/
│       └── EngagementsRepository.kt
├── di/
│   └── AppModule.kt
├── domain/
│   └── model/
│       ├── Engagement.kt
│       ├── EngagementDisplayMode.kt
│       ├── NewsArticle.kt
│       ├── PlayerState.kt
│       └── Station.kt
├── navigation/
│   ├── AppNavigation.kt
│   └── AppTab.kt
├── player/
│   ├── RadioPlaybackService.kt
│   └── RadioPlayerManager.kt
├── tuneurl/
│   ├── CapturingRenderersFactory.kt
│   ├── StreamAudioCapture.kt
│   ├── StreamDataCapture.kt
│   ├── TimeUtils.kt
│   ├── TuneURLDetector.kt
│   ├── TuneURLManager.kt
│   └── TuneURLModels.kt
├── ui/
│   ├── components/
│   │   ├── EngagementListCard.kt
│   │   ├── EngagementSheet.kt
│   │   ├── ListeningControlButton.kt
│   │   ├── MiniPlayerView.kt
│   │   └── StationArtwork.kt
│   ├── main/
│   │   ├── MainScreen.kt
│   │   └── MainViewModel.kt
│   ├── screens/
│   │   ├── news/
│   │   ├── player/
│   │   ├── saved/
│   │   ├── settings/
│   │   ├── stations/
│   │   └── turls/
│   └── theme/
├── MainActivity.kt
└── TuneURLRadioApp.kt

tuneurl_sdk/
├── src/main/
│   ├── cpp/           # Native C++ fingerprinting code
│   └── java/          # SDK Java/Kotlin wrappers
└── build.gradle.kts
```

---

## Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

---

## Dependencies

| Library             | Version    | Purpose              |
| ------------------- | ---------- | -------------------- |
| Jetpack Compose BOM | 2024.04.01 | UI Framework         |
| Material 3          | Latest     | Design System        |
| Hilt                | 2.51.1     | Dependency Injection |
| Room                | 2.6.1      | Local Database       |
| DataStore           | 1.1.1      | Preferences          |
| Navigation Compose  | 2.7.7      | Navigation           |
| Media3 ExoPlayer    | 1.3.1      | Audio Playback       |
| Coil                | 2.6.0      | Image Loading        |
| Retrofit            | 2.9.0      | HTTP Client          |
| OkHttp              | 4.12.0     | HTTP Client          |
| Coroutines          | 1.8.0      | Async Operations     |
| Gson                | 2.10.1     | JSON Parsing         |

---

## Build & Run

```bash
# Debug build
./gradlew :app:assembleDebug

# Install on device
./gradlew :app:installDebug

# Run tests
./gradlew :app:testDebugUnitTest
```

---

## Key Classes Reference

| Class                   | Purpose                                           |
| ----------------------- | ------------------------------------------------- |
| `MainActivity`          | Entry point, SDK initialization, permissions      |
| `MainViewModel`         | Central state management for playback & detection |
| `RadioPlayerManager`    | MediaController wrapper for audio playback        |
| `RadioPlaybackService`  | Background media service                          |
| `TuneURLManager`        | Detection lifecycle management                    |
| `TuneURLDetector`       | Fingerprint extraction & API search               |
| `StreamDataCapture`     | HTTP stream data capture                          |
| `EngagementSheet`       | Match result UI with WebView                      |
| `EngagementsRepository` | Data layer for engagements                        |
| `SettingsDataStore`     | App preferences storage                           |
