package skuber.examples.autoscaler

import org.apache.pekko.actor.ActorSystem
import skuber.api.client.RequestLoggingContext
import skuber.model.Pod
import skuber.json.format.podFormat
import skuber.operator.reconciler.*

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

/**
 * Reconciler for Autoscaler resources.
 *
 * This reconciler:
 * 1. Lists Pods matching the Autoscaler's selector
 * 2. Counts running replicas
 * 3. Updates the Autoscaler status
 * 4. Calculates desired replicas based on (simulated) metrics
 *
 * The controller is configured to also watch Pods, so any Pod change
 * triggers re-reconciliation of the owning Autoscaler.
 */
class AutoscalerReconciler(using system: ActorSystem) extends Reconciler[Autoscaler]:

  import AutoscalerResource.given

  given ExecutionContext = system.dispatcher
  given RequestLoggingContext = RequestLoggingContext()

  /** Label we add to Pods to track which Autoscaler manages them */
  val OwnerLabel = "scaling.example.com/autoscaler"

  override def reconcile(resource: Autoscaler, ctx: ReconcileContext[Autoscaler]): Future[ReconcileResult] =
    val log = ctx.log
    val autoscaler = resource
    val ns = autoscaler.metadata.namespace

    log.info(s"Reconciling Autoscaler ${autoscaler.name}")

    for
      // Step 1: List all Pods in the namespace matching the selector
      matchingPods <- listMatchingPods(autoscaler, ctx)

      // Step 2: Count running replicas
      runningPods = matchingPods.filter(isRunning)
      currentReplicas = runningPods.size
      _ = log.info(s"Found $currentReplicas running pods matching selector")

      // Step 3: Calculate desired replicas (simplified - in production you'd query metrics)
      desiredReplicas = calculateDesiredReplicas(autoscaler, currentReplicas)

      // Step 4: Update status if changed
      _ <- updateStatusIfNeeded(autoscaler, currentReplicas, desiredReplicas, ctx)

      // Step 5: If scaling is needed, you would create/delete Pods here
      // For this example, we just log what would happen
      _ = if desiredReplicas != currentReplicas then
        log.info(s"Scaling needed: current=$currentReplicas, desired=$desiredReplicas")
        // In a real operator, you'd create a Deploymcent or ReplicaSet,
        // or directly manage Pods here

    yield
      // Requeue periodically to check metrics
      ReconcileResult.RequeueAfter(30.seconds, "Periodic metrics check")

  /**
   * List Pods matching the Autoscaler's selector labels (from cache).
   */
  private def listMatchingPods(
    autoscaler: Autoscaler,
    ctx: ReconcileContext[Autoscaler]
  ): Future[List[Pod]] =
    val ns = autoscaler.metadata.namespace
    val selector = autoscaler.spec.selector

    Future.successful(
      ctx.listCachedInNamespace[Pod](ns).filter { pod =>
        selector.forall { case (key, value) =>
          pod.metadata.labels.get(key).contains(value)
        }
      }
    )

  /**
   * Check if a Pod is running.
   */
  private def isRunning(pod: Pod): Boolean =
    pod.status.exists { status =>
      status.phase.contains(Pod.Phase.Running) &&
      status.containerStatuses.forall(_.ready)
    }

  /**
   * Calculate desired replicas based on current state and metrics.
   *
   * This is simplified - a real autoscaler would:
   * - Query metrics server for CPU/memory usage
   * - Apply smoothing to avoid thrashing
   * - Consider scale-down stabilization windows
   */
  private def calculateDesiredReplicas(autoscaler: Autoscaler, currentReplicas: Int): Int =
    val spec = autoscaler.spec

    // Simulated scaling logic - in production, query actual metrics
    // For demo, we just ensure we're within bounds
    val desired = currentReplicas match
      case 0 => spec.minReplicas  // Scale up from zero
      case n => n                  // Maintain current (no metrics to base decision on)

    // Clamp to min/max bounds
    math.max(spec.minReplicas, math.min(spec.maxReplicas, desired))

  /**
   * Update the Autoscaler status if it has changed.
   */
  private def updateStatusIfNeeded(
    autoscaler: Autoscaler,
    currentReplicas: Int,
    desiredReplicas: Int,
    ctx: ReconcileContext[Autoscaler]
  ): Future[Unit] =
    val currentStatus = autoscaler.status.getOrElse(AutoscalerResource.Status())

    val statusChanged =
      currentStatus.currentReplicas != currentReplicas ||
      currentStatus.desiredReplicas != desiredReplicas

    if statusChanged then
      val message = if currentReplicas == desiredReplicas then
        s"Running $currentReplicas replicas"
      else
        s"Scaling from $currentReplicas to $desiredReplicas replicas"

      val newStatus = AutoscalerResource.Status(
        currentReplicas = currentReplicas,
        desiredReplicas = desiredReplicas,
        lastObservedCPU = None,  // Would come from metrics
        message = message
      )

      val updated = autoscaler.copy(status = Some(newStatus))
      ctx.client.usingNamespace(autoscaler.metadata.namespace)
        .updateStatus(updated)
        .map(_ => ())
        .recover { case e =>
          ctx.log.warn(s"Failed to update status: ${e.getMessage}")
        }
    else
      Future.successful(())
