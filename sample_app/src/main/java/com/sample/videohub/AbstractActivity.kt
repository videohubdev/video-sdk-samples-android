package com.sample.videohub

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.sample.videohub.video.GroupVideoChatActivity
import com.sample.videohub.video.VideoViewModel
import kotlin.properties.ReadOnlyProperty


abstract class AbstractActivity: AppCompatActivity() {

    fun requestNeededPermissions(
        permissions: List<String> = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
        onHasPermissions: () -> Unit
    ) {
        val requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
                var hasDenied = false
                // Check if any permissions weren't granted.
                for (grant in grants.entries) {
                    if (!grant.value) {
                        Toast.makeText(this, "Missing permission: ${grant.key}", Toast.LENGTH_SHORT).show()

                        hasDenied = true
                    }
                }

                if (!hasDenied) {
                    onHasPermissions()
                }
            }

        // Assemble the needed permissions to request
        val neededPermissions = permissions
            .filter { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_DENIED }
            .toTypedArray()

        if (neededPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(neededPermissions)
        } else {
            onHasPermissions()
        }
    }

}
