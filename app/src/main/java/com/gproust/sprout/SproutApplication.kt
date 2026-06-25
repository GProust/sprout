package com.gproust.sprout

import android.app.Application
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.SproutDatabase

/**
 * Application entry point. Owns the database and repository for the
 * lifetime of the process (a lightweight manual dependency container).
 */
class SproutApplication : Application() {

    val repository: SproutRepository by lazy {
        SproutRepository(SproutDatabase.getInstance(this))
    }
}
