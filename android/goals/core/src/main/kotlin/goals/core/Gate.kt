package goals.core

import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** Whether apps are gated right now, judged from the last status the app fetched. */
sealed interface GateState {
    /** Rest day, done, between windows, or opened by the allowance or an unlock code: nothing is gated. */
    data object Open : GateState

    /** In the window of a workout not done yet ([session]; null from servers before windows): apps outside the allowlist are gated. */
    data class Locked(val status: Status, val session: Session? = null) : GateState

    /**
     * The cached status can't vouch for today (never fetched, from an earlier day, or the
     * device clock is behind the last check). Gate until a fresh check says otherwise, so
     * going offline or winding the clock back doesn't unlock anything.
     */
    data class Unknown(val reason: String) : GateState
}

/** The zone "today" is judged in: the sheet's, from [cached], else [deviceZone]. */
fun statusZone(cached: Status?, deviceZone: ZoneId): ZoneId = cached?.timeZone?.let { zoneOrNull(it) } ?: deviceZone

/**
 * Judge [cached] (the last status fetched, or null) at [now], with what this device opened
 * itself ([unlocks]: the allowance, an unlock code). "Today" and the time of day are computed in
 * the sheet's time zone from the status, falling back to [deviceZone].
 */
fun gateState(cached: Status?, now: Instant, deviceZone: ZoneId, unlocks: Unlocks = Unlocks()): GateState {
    val zone = statusZone(cached, deviceZone)
    val checkedAt = cached?.serverTime?.let { instantOrNull(it) }
    val clockBehind = checkedAt != null && now.isBefore(checkedAt.minusSeconds(CLOCK_SLACK_SECONDS))
    // now.atZone(zone).toLocalDate(), not LocalDate.ofInstant: that needs API 34, the tablet is 33.
    val today = now.atZone(zone).toLocalDate().toString()
    val base = when {
        cached == null -> GateState.Unknown("Not checked yet.")
        clockBehind -> GateState.Unknown("The clock is behind the last check.")
        today != cached.today -> GateState.Unknown("Last checked ${cached.today}.")
        else -> {
            val minute = minuteOfDay(now, zone)
            if (cached.gatedAt(minute)) GateState.Locked(cached, cached.gatedSession(minute)) else GateState.Open
        }
    }
    if (base == GateState.Open) return base
    if (allowanceEnds(unlocks, now) != null) return GateState.Open
    if (!clockBehind && unlocks.openDay == today) return GateState.Open
    return base
}

/**
 * Whether opening [packageName] should show the gate. [alwaysAllowed] (this app, the
 * launcher, Settings) never is. While locked or unknown, only the allowlist of the last
 * status fetched is let through. When that status is from an earlier day the list may be
 * out of date until the next successful check.
 */
fun shouldGate(packageName: String, state: GateState, cached: Status?, alwaysAllowed: Set<String>): Boolean =
    when {
        state is GateState.Open -> false
        packageName in alwaysAllowed -> false
        else -> packageName !in (cached?.allowlist ?: emptyList())
    }

/** Device clock may run this far behind the server's before a cached status stops counting. */
private const val CLOCK_SLACK_SECONDS = 5 * 60L

/** The allowance: this long open while gated, once per [ALLOWANCE_EVERY] (from when it was started). */
val ALLOWANCE: Duration = Duration.ofMinutes(2)
val ALLOWANCE_EVERY: Duration = Duration.ofHours(1)

/** When the running allowance ends, or null when none is running. */
fun allowanceEnds(u: Unlocks, now: Instant): Instant? {
    val start = u.allowanceStart?.let(Instant::ofEpochMilli) ?: return null
    val end = start.plus(ALLOWANCE)
    return if (!now.isBefore(start) && now.isBefore(end)) end else null
}

/**
 * When the allowance can be started again, or null if it can now: [ALLOWANCE_EVERY] after it was
 * last started. Winding the clock back only puts that further away.
 */
fun allowanceReady(u: Unlocks, now: Instant): Instant? {
    val ready = u.allowanceStart?.let(Instant::ofEpochMilli)?.plus(ALLOWANCE_EVERY) ?: return null
    return if (now.isBefore(ready)) ready else null
}

private fun zoneOrNull(id: String): ZoneId? = try { ZoneId.of(id) } catch (e: DateTimeException) { null }

private fun instantOrNull(s: String): Instant? = try { Instant.parse(s) } catch (e: DateTimeParseException) { null }
