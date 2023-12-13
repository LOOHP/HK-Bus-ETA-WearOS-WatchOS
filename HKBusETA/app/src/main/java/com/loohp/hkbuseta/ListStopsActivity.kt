/*
 * This file is part of HKBusETA.
 *
 * Copyright (C) 2023. LoohpJames <jamesloohp@gmail.com>
 * Copyright (C) 2023. Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.loohp.hkbuseta

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Stable
import com.google.android.horologist.compose.ambient.AmbientAware
import com.loohp.hkbuseta.app.ListStopsMainElement
import com.loohp.hkbuseta.appcontext.appContext
import com.loohp.hkbuseta.objects.RouteSearchResultEntry
import com.loohp.hkbuseta.shared.Shared
import com.loohp.hkbuseta.utils.ifFalse
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit


@Stable
class ListStopsActivity : ComponentActivity() {

    private val sync: ExecutorService = Executors.newSingleThreadExecutor()
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(16)
    private val etaUpdatesMap: MutableMap<Int, Pair<ScheduledFuture<*>?, () -> Unit>> = LinkedHashMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shared.ensureRegistryDataAvailable(this).ifFalse { return }
        Shared.setDefaultExceptionHandler(this)

        val route = intent.extras!!.getByteArray("route")?.let { runBlocking { RouteSearchResultEntry.deserialize(ByteReadChannel(it)) } }!!
        val scrollToStop = intent.extras!!.getString("scrollToStop")
        val showEta = intent.extras!!.getBoolean("showEta", true)
        val isAlightReminder = intent.extras!!.getBoolean("isAlightReminder", false)

        setContent {
            AmbientAware {
                ListStopsMainElement(it, appContext, route, showEta, scrollToStop, isAlightReminder) { isAdd, index, task ->
                    sync.execute {
                        if (isAdd) {
                            etaUpdatesMap.computeIfAbsent(index) { executor.scheduleWithFixedDelay(task, 0, Shared.ETA_UPDATE_INTERVAL.toLong(), TimeUnit.MILLISECONDS) to task!! }
                        } else {
                            etaUpdatesMap.remove(index)?.first?.cancel(true)
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Shared.setSelfAsCurrentActivity(this)
    }

    override fun onResume() {
        super.onResume()
        sync.execute {
            etaUpdatesMap.replaceAll { _, value ->
                value.first?.cancel(true)
                executor.scheduleWithFixedDelay(value.second, 0, Shared.ETA_UPDATE_INTERVAL.toLong(), TimeUnit.MILLISECONDS) to value.second
            }
        }
    }

    override fun onPause() {
        super.onPause()
        sync.execute {
            etaUpdatesMap.forEach { it.value.first?.cancel(true) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            executor.shutdownNow()
            sync.shutdownNow()
            Shared.removeSelfFromCurrentActivity(this)
        }
    }

}