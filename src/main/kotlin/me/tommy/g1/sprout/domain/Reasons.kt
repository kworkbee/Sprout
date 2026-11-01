package me.tommy.g1.sprout.domain

enum class Reason {
    InvalidTTL,
    NamespaceConflict,
    NamespaceTerminating,
    SecretUnavailable,
    Progressing,
    WorkloadsAvailable,
    TTLExpired,
    CleanupStuck,
}

enum class EventType { Normal, Warning }
