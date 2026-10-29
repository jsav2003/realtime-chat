package com.jsav.chat.harness;

/**
 * Los números de una corrida, aparte de las invariantes. Sirven para que "cero violaciones" se
 * pueda leer con contexto: cuánto se envió, cuánta falla se inyectó y cuánto hizo falta recuperar.
 */
public record RunSummary(
    Scenario scenario,
    long durationMillis,
    int plannedSends,
    int confirmedSends,
    int unconfirmedSends,
    long forcedResends,
    long retriedRequests,
    long rateLimitedRequests,
    int disconnectsInjected,
    Instance restartedInstance,
    long restartMillis,
    String restartError,
    int serverMessages,
    long lastSeq,
    long firstSeenLive,
    long firstSeenByResync,
    long repeatedDeliveries,
    long missingBeforeFinalResync,
    int totalConnects,
    int totalResyncs,
    String hardware) {}
