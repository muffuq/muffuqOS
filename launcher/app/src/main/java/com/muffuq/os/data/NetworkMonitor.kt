package com.muffuq.os.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

enum class NetworkKind { None, Wifi, Ethernet }

fun Context.networkKind(): Flow<NetworkKind> = callbackFlow {
    val cm = getSystemService(ConnectivityManager::class.java)

    fun kindOf(caps: NetworkCapabilities?) = when {
        caps == null -> NetworkKind.None
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.Ethernet
        else -> NetworkKind.Wifi
    }

    trySend(kindOf(cm.getNetworkCapabilities(cm.activeNetwork)))
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            trySend(kindOf(caps))
        }

        override fun onLost(network: Network) {
            trySend(NetworkKind.None)
        }
    }
    cm.registerDefaultNetworkCallback(callback)
    awaitClose { cm.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
