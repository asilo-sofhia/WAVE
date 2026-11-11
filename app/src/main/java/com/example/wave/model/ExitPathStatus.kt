package com.example.wave.model

/** Result of checking where traffic leaves the network. Names are a contract with the backend: keep stable. */
enum class ExitPathStatus { PENDING, PASSED, FAILED }
