/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.jetnews.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.launchInComposition
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.example.jetnews.data.Result
import com.example.jetnews.ui.state.UiState
import com.example.jetnews.ui.state.copyWithResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect

/**
 * Result object for [launchUiStateProducer].
 *
 * It is intended that you destructure this class at the call site:
 *
 * ```
 * val (result, refresh, clearError) = dataSource.launchUiStateProducer { loadData() }
 * ```
 */
data class ProducerResult<T>(
    val result: State<T>,
    val onRefresh: () -> Unit,
    val onClearError: () -> Unit
)

/**
 * Launch a coroutine to create refreshable [UiState] from a suspending producer.
 *
 * It is intended that you destructure this at the call site:
 *
 * ```
 * val (result, refresh, clearError) = dataSource.launchUiStateProducer { loadData() }
 * ```
 *
 * Repeated calls to onRefresh are conflated while a request is in progress.
 *
 * @param producer the data source to loading data from
 * @param block suspending lambda that produces a single value from the data source
 * @return data state, onRefresh event, and onClearError event
 */
@Composable
fun <Producer, T> launchUiStateProducer(
    producer: Producer,
    block: suspend Producer.() -> Result<T>
): ProducerResult<UiState<T>> = launchUiStateProducer(producer, Unit, block)

/**
 * Launch a coroutine to create refreshable [UiState] from a suspending producer.
 *
 * It is intended that you destructure this at the call site:
 *
 * ```
 * val (result, refresh, clearError) = dataSource.launchUiStateProducer(dataId) { loadData(dataId) }
 * ```
 *
 * Repeated calls to onRefresh are conflated while a request is in progress.
 *
 * @param producer the data source to loading data from
 * @param v1 any argument used by production lambda, such as a resource ID
 * @param block suspending lambda that produces a single value from the data source
 * @return data state, onRefresh event, and onClearError event
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Composable
fun <Producer, T> launchUiStateProducer(
    producer: Producer,
    v1: Any,
    block: suspend Producer.() -> Result<T>
): ProducerResult<UiState<T>> {
    val producerState = remember { mutableStateOf(UiState<T>(loading = true)) }

    // posting to this channel will trigger a single refresh
    val refreshChannel =
        remember { Channel<Unit>(Channel.CONFLATED) }

    // event for caller to trigger a refresh
    val refresh: () -> Unit = { refreshChannel.offer(Unit) }

    // event for caller to clear any errors on the current result (useful for transient error
    // displays)
    val clearError: () -> Unit = {
        producerState.value = producerState.value.copy(exception = null)
    }

    // whenever Producer or v1 changes, launch a new coroutine to call block() and refresh whenever
    // the onRefresh callback is called
    launchInComposition(producer, v1) {
        // whenever the coroutine restarts, clear the previous result immediately as they are no
        // longer valid
        producerState.value = UiState(loading = true)
        // force a refresh on coroutine restart
        refreshChannel.send(Unit)
        // whenever a refresh is triggered, call block again
        for(refreshEvent in refreshChannel) {
            producerState.value = producerState.value.copy(loading = true)
            producerState.value = producerState.value.copyWithResult(producer.block())
        }
    }
    return ProducerResult(producerState, refresh, clearError)
}