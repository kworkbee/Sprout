package me.tommy.g1.sprout.reconcile

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Namespace
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration
import io.javaoperatorsdk.operator.api.reconciler.Cleaner
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext
import io.javaoperatorsdk.operator.api.reconciler.Reconciler
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl
import io.javaoperatorsdk.operator.processing.event.ResourceID
import io.javaoperatorsdk.operator.processing.event.source.EventSource
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource
import io.javaoperatorsdk.operator.processing.retry.GradualRetry
import me.tommy.g1.sprout.api.PreviewEnvironment
import me.tommy.g1.sprout.config.SproutProperties
import me.tommy.g1.sprout.domain.ENV_LABEL
import me.tommy.g1.sprout.domain.EventType
import me.tommy.g1.sprout.domain.MANAGED_BY
import me.tommy.g1.sprout.domain.MANAGER
import me.tommy.g1.sprout.domain.NamespaceState
import me.tommy.g1.sprout.domain.Reason
import me.tommy.g1.sprout.domain.TtlDecision
import me.tommy.g1.sprout.domain.decideTtl
import me.tommy.g1.sprout.domain.envName
import me.tommy.g1.sprout.domain.namespaceName
import me.tommy.g1.sprout.domain.readiness
import me.tommy.g1.sprout.domain.stateFor
import me.tommy.g1.sprout.infra.EventPublisher
import me.tommy.g1.sprout.infra.SproutMetrics
import me.tommy.g1.sprout.manifest.DesiredContext
import me.tommy.g1.sprout.manifest.desiredNamespace
import me.tommy.g1.sprout.manifest.guardRails
import me.tommy.g1.sprout.manifest.watchedKinds
import me.tommy.g1.sprout.manifest.workloads
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private const val RETRY_INITIAL_MS = 2000L
private const val RETRY_MAX_MS = 60_000L
private val CONFLICT_RETRY = 30.seconds.toJavaDuration()
private val TERMINATING_RETRY = 3.seconds.toJavaDuration()
private val SECRET_RETRY = 15.seconds.toJavaDuration()
private val SECRET_POLL = 5.minutes.toJavaDuration()

internal fun primaryOf(r: HasMetadata): Set<ResourceID> =
    r.metadata.labels
        .orEmpty()[ENV_LABEL]
        ?.let { setOf(ResourceID(it)) } ?: emptySet()

@GradualRetry(
    maxAttempts = Int.MAX_VALUE,
    initialInterval = RETRY_INITIAL_MS,
    intervalMultiplier = 2.0,
    maxInterval = RETRY_MAX_MS,
)
@ControllerConfiguration
class PreviewEnvironmentReconciler(
    private val cluster: Cluster,
    private val props: SproutProperties,
    private val metrics: SproutMetrics,
    private val events: EventPublisher,
    private val secrets: SecretSynchronizer,
    private val cleaner: EnvironmentCleaner,
) : Reconciler<PreviewEnvironment>,
    Cleaner<PreviewEnvironment> {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun prepareEventSources(
        context: EventSourceContext<PreviewEnvironment>,
    ): List<EventSource<*, PreviewEnvironment>> =
        (listOf(Namespace::class) + watchedKinds).map { type ->
            InformerEventSource(
                InformerEventSourceConfiguration
                    .from(type.java, PreviewEnvironment::class.java)
                    .withLabelSelector("$MANAGED_BY=$MANAGER")
                    .withSecondaryToPrimaryMapper(::primaryOf)
                    .build(),
            )
        }

    override fun reconcile(
        env: PreviewEnvironment,
        context: Context<PreviewEnvironment>,
    ): UpdateControl<PreviewEnvironment> {
        val now = Instant.now()
        val status = EnvironmentStatus(env, now, props.ingress)
        val requeue = Requeue()
        return when (val ttl = env.decideTtl(now)) {
            is TtlDecision.Invalid -> {
                status.recordNotReady(Reason.InvalidTTL, "ttl '${ttl.raw}' is not a valid duration")
                UpdateControl.patchStatus(env)
            }
            is TtlDecision.Expired -> expire(env, ttl)
            else -> {
                val running = ttl as? TtlDecision.Running
                status.recordExpiry(running?.expiresAt)
                running?.let { requeue.earliest(it.remaining) }
                provision(env, status, requeue)
            }
        }
    }

    override fun cleanup(
        env: PreviewEnvironment,
        context: Context<PreviewEnvironment>,
    ): DeleteControl = cleaner.cleanup(env)

    private fun expire(
        env: PreviewEnvironment,
        ttl: TtlDecision.Expired,
    ): UpdateControl<PreviewEnvironment> {
        log.info("TTL expired for {}, deleting", env.envName)
        metrics.expired.increment()
        events.emit(env, EventType.Normal, Reason.TTLExpired, "ttl ${ttl.raw} elapsed, deleting environment")
        cluster.delete(env)
        return UpdateControl.noUpdate()
    }

    private fun provision(
        env: PreviewEnvironment,
        status: EnvironmentStatus,
        requeue: Requeue,
    ): UpdateControl<PreviewEnvironment> {
        val nsName = env.namespaceName
        return when (cluster.namespace(nsName).stateFor(env)) {
            NamespaceState.CONFLICT -> {
                metrics.conflicts.increment()
                val message = "namespace $nsName exists and is not managed by this environment"
                block(env, status, requeue, Reason.NamespaceConflict, message, CONFLICT_RETRY)
            }
            NamespaceState.TERMINATING ->
                block(
                    env,
                    status,
                    requeue,
                    Reason.NamespaceTerminating,
                    "waiting for namespace $nsName to finish terminating",
                    TERMINATING_RETRY,
                )
            NamespaceState.USABLE -> {
                cluster.ensure(env.desiredNamespace())
                cluster.ensureAll(env, guardRails, DesiredContext(props))
                syncWorkloads(env, status, requeue)
            }
        }
    }

    private fun syncWorkloads(
        env: PreviewEnvironment,
        status: EnvironmentStatus,
        requeue: Requeue,
    ): UpdateControl<PreviewEnvironment> =
        when (val secret = secrets.sync(env)) {
            is SecretOutcome.Unavailable ->
                block(env, status, requeue, Reason.SecretUnavailable, secret.message, SECRET_RETRY)
            else -> {
                val hash = (secret as? SecretOutcome.Copied)?.hash
                hash?.let { requeue.earliest(SECRET_POLL) }
                val applied = cluster.ensureAll(env, workloads, DesiredContext(props, hash))
                status.recordReadiness(applied.filterIsInstance<Deployment>().single().readiness())
                val control = UpdateControl.patchStatus(env)
                requeue.orNull()?.let { control.rescheduleAfter(it) } ?: control
            }
        }

    private fun block(
        env: PreviewEnvironment,
        status: EnvironmentStatus,
        requeue: Requeue,
        reason: Reason,
        message: String,
        retry: Duration,
    ): UpdateControl<PreviewEnvironment> {
        val alreadyReported = status.wasReason(reason)
        status.recordNotReady(reason, message)
        if (!alreadyReported) events.emit(env, EventType.Warning, reason, message)
        return UpdateControl.patchStatus(env).rescheduleAfter(requeue.earliest(retry))
    }
}
