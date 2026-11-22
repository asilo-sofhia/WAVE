package com.example.wave.model

/**
 * Result of checking where traffic leaves the network. Names are a contract with the backend: keep stable.
 * UNREACHABLE: no answer from the WAVE server (3 tries in 30 s, CONTRACT §4). It doesn't change the verdict,
 * but the user is told not to enter passwords yet.
 */
enum class ExitPathStatus { PENDING, PASSED, FAILED, UNREACHABLE }
