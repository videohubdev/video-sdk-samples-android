package com.sample.videohub.utils

import dev.videohub.android.room.participant.LocalParticipant
import dev.videohub.android.room.participant.Participant
import dev.videohub.android.room.participant.RemoteParticipant
import dev.videohub.android.room.track.Track
import dev.videohub.android.room.track.VideoTrack
import dev.videohub.android.util.flow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.collections.emptyList


/*
session.local.participant.singleVideoTrackFlow().collectLatest { track -> }

 */


fun Participant.singleVideoTrackFlow(
    includeCamera: Boolean = true,
    includeScreenShare: Boolean = true
): Flow<VideoTrack?> {

    return ::videoTrackPublications.flow
        .map { publications ->

            val preferredPublication = when {
                includeScreenShare -> {
                    getTrackPublication(Track.Source.SCREEN_SHARE)
                }
                else -> null
            } ?: when {
                includeCamera -> {
                    getTrackPublication(Track.Source.CAMERA)
                }
                else -> null
            } ?: publications.firstOrNull()?.first

            preferredPublication?.track as? VideoTrack
        }
        .distinctUntilChanged()
}

/*
 sessionPK?.remote?.participants?.remoteVideoTracksFlow()?.collectLatest { tracks ->  }

 */
fun Flow<Map<Participant.Identity, RemoteParticipant>>.remoteVideoTracksFlow(
    includeCamera: Boolean = true,
    includeScreenShare: Boolean = true
): Flow<List<VideoTrack>> {

    return flatMapLatest { participantsMap ->
        val trackFlows = participantsMap.values.map { participant ->
            participant.singleVideoTrackFlow(
                includeCamera = includeCamera,
                includeScreenShare = includeScreenShare
            )
        }

        if (trackFlows.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(trackFlows) { tracks ->
                tracks
                    .filterNotNull()
                    .distinct()
                    .toList()
            }
        }
    }
}

/*
local track control
 */
suspend fun LocalParticipant.publishVideoTrack(withCamera: Boolean = true){
    val track = getOrCreateDefaultVideoTrack()
    if (withCamera) {
        setCameraEnabled(true)
    }
    publishVideoTrack(track)
}
