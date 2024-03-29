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

package com.loohp.hkbuseta.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.loohp.hkbuseta.MainActivity
import com.loohp.hkbuseta.R
import com.loohp.hkbuseta.appcontext.appContext
import com.loohp.hkbuseta.common.objects.Operator
import com.loohp.hkbuseta.common.objects.Route
import com.loohp.hkbuseta.common.objects.Stop
import com.loohp.hkbuseta.common.objects.asStop
import com.loohp.hkbuseta.common.objects.getDisplayName
import com.loohp.hkbuseta.common.objects.getDisplayRouteNumber
import com.loohp.hkbuseta.common.shared.Registry
import com.loohp.hkbuseta.common.shared.Registry.StopData
import com.loohp.hkbuseta.common.shared.Shared
import com.loohp.hkbuseta.common.utils.LocationResult
import com.loohp.hkbuseta.utils.getGPSLocation
import com.loohp.hkbuseta.utils.getOr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference


@Immutable
data class AlightReminderData(
    val active: Boolean,
    val currentLocation: LocationResult,
    val distance: Double,
    val targetStop: Stop,
    val index: Int,
    val route: Route,
    val operator: Operator,
    val allStops: List<StopData>
) {

    constructor(active: Boolean, targetStop: Stop, index: Int, route: Route, operator: Operator, allStops: List<StopData>):
        this(active, LocationResult.FAILED_RESULT, Double.MAX_VALUE, targetStop, index, route, operator, allStops)

    fun updateLocation(currentLocation: LocationResult, distance: Double): AlightReminderData {
        return AlightReminderData(active, currentLocation, distance, targetStop, index, route, operator, allStops)
    }

    fun setActive(active: Boolean): AlightReminderData {
        return AlightReminderData(active, currentLocation, distance, targetStop, index, route, operator, allStops)
    }

}

@Immutable
data class BuildDataResult(
    val notificationBuilder: NotificationCompat.Builder,
    val sameNotificationText: Boolean,
    val overshot: Boolean,
    val isTargetStopClosest: Boolean
)

@Stable
class AlightReminderService : Service() {

    companion object {

        private val current: MutableStateFlow<AlightReminderData?> = MutableStateFlow(null)
        private val activeInstance: AtomicReference<AlightReminderService> = AtomicReference()

        fun getCurrentState(): StateFlow<AlightReminderData?> {
            return current
        }

        fun getCurrentValue(): AlightReminderData? {
            return current.value
        }

        fun updateCurrentValue(data: AlightReminderData) {
            current.value = data
        }

        fun terminate() {
            activeInstance.updateAndGet { it?.stopSelf(); null }
        }

    }

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var future: Future<*>? = null
    private var lastNotificationText: String = ""
    private var wakeLock: WakeLock? = null

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    private fun buildData(currentLocation: LocationResult, distance: Double, init: Boolean): BuildDataResult {
        val currentValue = getCurrentValue()!!
        val operatorName = currentValue.operator.getDisplayName(currentValue.route.routeNumber, currentValue.route.isKmbCtbJoint, Shared.language)
        val routeNumber = currentValue.operator.getDisplayRouteNumber(currentValue.route.routeNumber)
        val approachDistance = if (currentValue.index <= 1) {
            0.7
        } else {
            (currentValue.allStops[currentValue.index - 1].stop.location.distance(currentValue.allStops[currentValue.index - 2].stop.location) * 0.75).coerceIn(0.7, 1.5)
        }
        val text = operatorName.plus(" ").plus(routeNumber).plus(" ")
            .plus(if (init) {
                (if (Shared.language == "en") "Going to" else "正在前往").plus("\n").plus(currentValue.targetStop.name[Shared.language])
            } else {
                when (distance) {
                    in -Double.MAX_VALUE..0.3 -> (if (Shared.language == "en") "\nArrived at " else "\n已到達 ").plus(currentValue.targetStop.name[Shared.language])
                    in 0.3..approachDistance -> (if (Shared.language == "en") "Attention!\nSoon Arriving at " else "注意!\n即將到達 ").plus(currentValue.targetStop.name[Shared.language])
                    else -> (if (Shared.language == "en") "Going to" else "正在前往").plus("\n").plus(currentValue.targetStop.name[Shared.language])
                }
            })
        val sameAsLast = if (!init) {
            val sameAsLast = lastNotificationText == text
            lastNotificationText = text
            sameAsLast
        } else {
            false
        }

        val closestStopIndex = if (currentLocation.isSuccess) {
            val location = currentLocation.location!!
            currentValue.allStops.withIndex().minBy {
                val stop = it.value.stop
                stop.location.distance(location)
            }.index + 1
        } else {
            -1
        }
        val overshot = closestStopIndex > currentValue.index
        val isTargetStopClosest = closestStopIndex == currentValue.index

        return BuildDataResult(NotificationCompat.Builder(this, "HK_BUS_ETA_ALIGHT")
            .setContentTitle(if (Shared.language == "en") "Alight Reminder" else "落車提示")
            .setContentText(text)
            .setSmallIcon(R.mipmap.icon)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE), sameAsLast, overshot, isTargetStopClosest)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activeInstance.updateAndGet { it?.stopSelf(); this }

        val newStop = intent?.extras?.getString("stop")?.let { Stop.deserialize(Json.decodeFromString<JsonObject>(it)) }
        val newRoute = intent?.extras?.getString("route")?.let { Route.deserialize(Json.decodeFromString<JsonObject>(it)) }
        val newOperator = intent?.extras?.getString("co")?.let { Operator.valueOf(it) }
        val newIndex = intent?.extras?.getInt("index")
        if (newStop != null && newRoute != null && newOperator != null && newIndex != null) {
            val allStops = Registry.getInstance(appContext).getAllStops(newRoute.routeNumber, newRoute.bound[newOperator]!!, newOperator, newRoute.gmbRegion)
            updateCurrentValue(AlightReminderData(true, newStop, newIndex, newRoute, newOperator, allStops))
            Firebase.analytics.logEvent("alight_reminder", Bundle().apply {
                putString("value", "${newRoute.routeNumber},${newOperator.name},${newRoute.bound[newOperator]},${newRoute.stops[newOperator]?.getOrNull(newIndex)?: "Unknown Stop $newIndex"}")
            })
        }
        val stopListIntentBuilder = getCurrentValue()!!.let { currentValue ->
            Registry.getInstance(appContext).findRoutes(currentValue.route.routeNumber, true) { it -> it == currentValue.route }.first().let { {
                val stopListIntent = Intent(this, MainActivity::class.java)
                stopListIntent.putExtra("stopRoute", it.toByteArray())
                stopListIntent.putExtra("scrollToStop", currentValue.route.stops[currentValue.operator]!!.minBy { it.asStop(appContext)!!.location.distance(currentValue.targetStop.location) })
                stopListIntent.putExtra("showEta", false)
                stopListIntent.putExtra("isAlightReminder", true)
            } }
        }

        if (wakeLock?.isHeld != true) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hkbuseta:alight_reminder")
            wakeLock!!.setReferenceCounted(false)
            wakeLock!!.acquire(10800000)
        }

        if (future == null) {
            executor.scheduleWithFixedDelay({
                val currentValue = getCurrentValue()
                if (currentValue?.active == true) {
                    val currentLocation = getGPSLocation(appContext).asCompletableFuture().getOr(10, TimeUnit.SECONDS) { currentValue.currentLocation }!!
                    if (currentLocation.isSuccess) {
                        val distance = currentValue.targetStop.location.distance(currentLocation.location!!)
                        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        val (notification, same, overshot, isTargetStopClosest) = buildData(currentLocation, distance, false)
                        val arrived = (distance <= 0.3 && isTargetStopClosest) || overshot
                        if (!same) {
                            if (!arrived) {
                                notification
                                    .setOngoing(true)
                                    .setContentIntent(PendingIntent.getActivity(this, ThreadLocalRandom.current().nextInt(0, Int.MAX_VALUE), stopListIntentBuilder.invoke(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                            } else {
                                notification.setOngoing(false)
                            }
                            notificationManager.notify(1, notification.build())
                        }
                        updateCurrentValue(currentValue.updateLocation(currentLocation, distance))
                        if (arrived) {
                            CoroutineScope(Dispatchers.IO).launch {
                                stopForeground(STOP_FOREGROUND_DETACH)
                                stopSelf()
                            }
                        }
                    }
                } else {
                    stopSelf()
                }
            }, 0, 5, TimeUnit.SECONDS)
        }

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            "HK_BUS_ETA_ALIGHT",
            "HK_BUS_ETA_ALIGHT",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        channel.description = "HK_BUS_ETA_ALIGHT"
        notificationManager.createNotificationChannel(channel)

        val notificationBuilder = buildData(LocationResult.FAILED_RESULT, Double.MAX_VALUE, true).notificationBuilder
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, ThreadLocalRandom.current().nextInt(0, Int.MAX_VALUE), stopListIntentBuilder.invoke(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

        val ongoingActivityStatus = Status.Builder()
            .addTemplate((if (Shared.language == "en") "Going to" else "正在前往").plus(" ").plus(getCurrentValue()!!.targetStop.name[Shared.language]))
            .build()

        val ongoingActivity = OngoingActivity.Builder(applicationContext, 1, notificationBuilder)
            .setStaticIcon(R.mipmap.icon)
            .setTouchIntent(PendingIntent.getActivity(this, ThreadLocalRandom.current().nextInt(0, Int.MAX_VALUE), stopListIntentBuilder.invoke(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setStatus(ongoingActivityStatus)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setTitle(if (Shared.language == "en") "Alight Reminder" else "落車提示")
            .build()

        ongoingActivity.apply(applicationContext)

        startForeground(1, notificationBuilder.build(), FOREGROUND_SERVICE_TYPE_LOCATION)

        return START_STICKY
    }

    override fun stopService(name: Intent?): Boolean {
        return super.stopService(name)
    }

    override fun onDestroy() {
        updateCurrentValue(getCurrentValue()!!.setActive(false))
        executor.shutdown()
        wakeLock?.release()
        super.onDestroy()
    }

}