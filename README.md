# video-sdk-samples-android

Lightweight browser SDK for building **real-time video, audio, and screen-sharing applications** using VideoHub infrastructure.
The SDK provides a simple API to connect users to rooms, publish media tracks, subscribe to remote participants, and handle real-time events.

It is designed to be **simple, extensible, and developer-friendly** while still exposing powerful real-time capabilities.

---

# Features

* Real-time **video and audio streaming**
* **Screen sharing**
* **Participant management**
* **Dynamic event system**
* **Remote track subscription**
* **Microphone control**
* Works in **modern browsers**
* Simple integration with **CDN or local script**

---

# Installation

Open the settings.gradle file in the project's root directory and add the Maven Central and Jitpack repositories, if it doesn't already exist:

```agsl
mavenCentral()
maven { url = uri("https://jitpack.io")
```
add the following to the dependencies block in your project module build.gradle file:

```agsl
implementation("dev.videohub:videohub-android:1.x.x")
implementation("dev.videohub:videohub-android-track-processors:1.x.x")
```

Add Permission to Manifest

```agsl
<uses-feature
        android:name="android.hardware.camera"
        android:required="false" />

<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```
---

# Basic Usage

## 1. Initialize the VideoHub engine

```kotlin
val config = VideoHubConfig(
    api_key = "vhub_live_Xxxxxxxxxxxxxxxxxxxxxxxxxxxxx--XxxXxxxxxxxx=",
    app_id = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
    app_secret = "*******************************************=",
    app_identifier = "livechat.com",
    app_platform = "Android",
    app_name = "Live Chat",
    domain = "livechat.com",
    username = "xyz@gmail.com",
    password = "***********",
    debug = true
)

VideoHubSDK.create(this, config)
```
### For Video rendering, add to your xml layout

```xml
  <dev.videohub.android.renderer.TextureViewRenderer
    android:id="@+id/renderer_local"
    android:layout_width="match_parent"
    android:layout_height="match_parent"  />
```

## Join a Room
Create and join video room (as Host)

```kotlin
val sdk = VideoHubSDK.videoHub
var session: RoomSession
session = sdk.join(roomName = room, audio = true, video = true)
```
## connect to room
connect/join to already existed room (as Guest)

```kotlin
val sdk = VideoHubSDK.videoHub
val session:RoomSession = sdk.connect(roomName = room)
```

## render local video

```kotlin
session?.initVideoRenderer(binding.rendererRemote)

session?.local?.videoTrack(Track.Source.CAMERA)
                ?.addRenderer(binding.rendererLocal)
```
## render remote video
Render remote video stream

```kotlin
var boundVideoTrackRemote: VideoTrack? = null
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.CREATED) {

        session!!.remote.videoTracks().collect { tracks ->
            val videoTrack = tracks.firstOrNull()

            if (boundVideoTrackRemote == videoTrack) return@collect

            boundVideoTrackRemote?.removeRenderer(binding.rendererRemote)
            boundVideoTrackRemote = videoTrack

            videoTrack?.addRenderer(binding.rendererRemote)

        }
    }
}
                    
```

# Publish Camera & Microphone

Start sending camera and microphone streams.
```kotlin
session.local.publishVideoStream()
session.local.publishAudioStream()
```

Stop camera and microphone:
```kotlin
session.local.unpublishVideoStream()
session.local.unpublishAudioStream()
```
---

# Toggle Camera AND Microphone

```kotlin
session.local.setCameraEnabled(isCameraEnabled)
session.local.setMicrophoneEnabled(isMicrophoneEnabled)
```
---

# Screen Sharing
## Start screen share

```kotlin
private fun requestMediaProjection() {
    val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    screenCaptureIntentLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
}

private val screenCaptureIntentLauncher =
    registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val resultCode = result.resultCode
        val data = result.data
        if (resultCode != RESULT_OK || data == null) {
            return@registerForActivityResult
        }

        lifecycleScope.launch {
            session.local.screen.startScreenShare(data)
        }
    }
```
## Stop screen share

```kotlin
session.local.screen.stopScreenShare()
```

## observe screen share state: enable / disable
```kotlin
 lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.CREATED) {
        viewModel.screen.isScreenShareEnabled.collect { enabled ->
            Log.e("groupVideo", "screen share1: $enabled")
            
        }
    }
}
```
---

# Event System

VideoHub provides a flexible **event emitter** to listen for real-time room events.

```kotlin
 session.observeEvents().collect {
            when (it) {
                is VideoHubEvent.Connected -> {}
                is VideoHubEvent.Disconnected -> {}
                is VideoHubEvent.Reconnecting -> {}
                is VideoHubEvent.Reconnected -> {}
                is VideoHubEvent.ParticipantConnected -> {}
                is VideoHubEvent.ParticipantDisconnected -> {}
                is VideoHubEvent.TrackSubscribed -> {}
                is VideoHubEvent.TrackUnsubscribed -> {}
                is VideoHubEvent.TrackPublished -> {}
                is VideoHubEvent.TrackUnpublished -> {}
                is VideoHubEvent.DataReceived -> {}
                is VideoHubEvent.ActiveSpeakersChanged -> {}
                is VideoHubEvent.ConnectionQualityChanged -> {}
                is VideoHubEvent.Error -> {}
            }

        }
```
---

# Leave Room
```kotlin
sdk.leaveRoom(session)

```
This will:
* disconnect from the room
* stop all media tracks
* clean up internal resources

# Delete Room (by Host)
```kotlin
sdk.clearRoom(session.roomName)
```

---

# Best Practices

* Always **destroy the room** when leaving.
* Handle **participantDisconnected** to clean UI.
* Handle **trackUnsubscribed** to remove video elements.

---


# License

MIT License

---

# Support

If you encounter issues or need help integrating VideoHub, please open an issue in the repository.

---

# Summary

VideoHub Client SDK enables developers to build **real-time video communication apps** with minimal setup.

With a simple API and flexible event system, you can quickly implement:

* video calls
* group meetings
* webinars
* screen sharing
* live collaboration

---

# Real-Time Messaging (RTM)

VideoHub provides built-in real-time messaging features including:

* Text messages
* Stickers
* GIFs / Animated Images
* Emoji Reactions
* File Attachments
* Call Invitations

---

# Send Text Message

```kotlin
val rtm:RMT = session
    .bindRtm()
    .rtm

rtm.sendChat(text)

```

---

# Receive Messages

```javascript
rtm?.on(EVENT.CHAT_MESSAGE){ jsonObject ->

}
```
---

# Send Sticker

```javascript
fun sendRichChat(map: Map<String, String>){
    rtm?.chat?.sendRichMessage(roomId, map)
}
```
---
# RTM Events

| Event                                 | Description                           |
| ------------------------------------- | ------------------------------------- |
| CHAT_MESSAGE                          | Text, sticker, GIF, reaction message  |
| CHAT_DELETE                           | Message deleted                       |
| TYPING_START                          | User started typing                   |
| TYPING_STOP                           | User stopped typing                   |
| USER_JOINED                           | User joined room                      |
| USER_LEFT                             | User left room                        |
| PRESENCE_UPDATE                       | Presence status update                |
| ROOM_STATE_SYNC                       | Room state synchronization            |

---

## Documentation

Full API documentation is available at:

https://docs.videohub.dev