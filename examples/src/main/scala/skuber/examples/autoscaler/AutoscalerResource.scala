package skuber.examples.autoscaler

import scala.annotation.experimental
import skuber.operator.crd.{CustomResourceDef, Scope, customResource}

/**
 * Autoscaler custom resource that watches Pods and maintains replica counts.
 *
 * This demonstrates watching secondary resources (Pods) to trigger reconciliation
 * when the observed state changes.
 */
@experimental
@customResource(
  group = "scaling.example.com",
  version = "v1",
  kind = "Autoscaler",
  scope = Scope.Namespaced,
  statusSubresource = true
)
object AutoscalerResource extends CustomResourceDef[AutoscalerResource.Spec, AutoscalerResource.Status]:

  /**
   * Spec defines the desired scaling behavior.
   */
  case class Spec(
    /** Label selector to match Pods (e.g., "app=myapp") */
    selector: Map[String, String],

    /** Minimum number of replicas */
    minReplicas: Int = 1,

    /** Maximum number of replicas */
    maxReplicas: Int = 10,

    /** Target CPU utilization percentage (for demo purposes) */
    targetCPUUtilization: Int = 80
  )

  /**
   * Status reflects the observed state.
   */
  case class Status(
    /** Current number of running replicas */
    currentReplicas: Int = 0,

    /** Desired number of replicas based on scaling logic */
    desiredReplicas: Int = 0,

    /** Last observed CPU utilization */
    lastObservedCPU: Option[Int] = None,

    /** Human-readable status message */
    message: String = "Initializing"
  )

/** Type alias for cleaner usage */
type Autoscaler = AutoscalerResource.Resource
