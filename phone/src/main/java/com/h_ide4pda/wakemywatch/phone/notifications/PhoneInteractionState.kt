package com.h_ide4pda.wakemywatch.phone.notifications

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

data class PhoneInteractionState(
    val interactive: Boolean,
    val locked: Boolean,
) {
    val isUnlockedInUse: Boolean get() = interactive && !locked
    val label: String
        get() = when {
            !interactive -> "screen_off"
            locked -> "locked"
            else -> "unlocked"
        }
}

object PhoneInteraction {
    fun read(context: Context): PhoneInteractionState {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val interactive = power.isInteractive
        val locked = runCatching { keyguard.isDeviceLocked || keyguard.isKeyguardLocked }
            .getOrDefault(keyguard.isKeyguardLocked)
        return PhoneInteractionState(interactive = interactive, locked = locked)
    }
}
