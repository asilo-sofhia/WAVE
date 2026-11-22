package com.example.wave.ui

import com.example.wave.data.RegistryState
import com.example.wave.model.Registry
import com.example.wave.ui.screens.nearby.registryVersionChanges
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** The Networks list re-judges itself when a different registry version arrives (not on the first value). */
class RegistryVersionChangesTest {

    private fun ready(version: Int) = RegistryState.Ready(
        Registry("wave-registry/1", version, "wave-dev", Instant.EPOCH, Instant.EPOCH, emptyList(), emptyList()),
    )

    @Test
    fun emitsOnlyWhenTheVersionChanges() = runBlocking {
        val state = MutableStateFlow<RegistryState>(RegistryState.Missing())
        val seen = mutableListOf<Int?>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { registryVersionChanges(state).collect { seen += it } }
        for (next in listOf(ready(1), ready(1).copy(), ready(2), RegistryState.Stale(ready(2).registry))) {
            state.value = next
            yield()
        }
        job.cancel()
        assertEquals(listOf<Int?>(1, 2), seen)
    }
}
