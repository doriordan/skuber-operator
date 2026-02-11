package skuber.examples.autoscaler

import org.apache.pekko.actor.ActorSystem
import skuber.api.client.RequestLoggingContext
import skuber.model.Pod
import skuber.json.format.podFormat
import skuber.operator.controller.{ControllerBuilder, ControllerManager, ManagerConfig, WatchNamespace}
import skuber.operator.reconciler.NamespacedName
import skuber.pekkoclient.k8sInit

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

/**
 * Autoscaler Operator - demonstrates watching secondary resources.
 *
 * This operator watches both:
 * 1. Autoscaler custom resources (primary)
 * 2. Pods matching the Autoscaler's selector (secondary)
 *
 * When any matching Pod changes, the owning Autoscaler is reconciled
 * to update its status with the current replica count.
 *
 * Usage:
 *   sbt "examples/runMain skuber.examples.autoscaler.AutoscalerOperator"
 */
object AutoscalerOperator:

  import AutoscalerResource.given

  /** Label used to identify which Autoscaler owns a Pod */
  val OwnerLabel = "scaling.example.com/autoscaler"

  def main(args: Array[String]): Unit =
    given system: ActorSystem = ActorSystem("autoscaler-operator")
    given ExecutionContext = system.dispatcher
    given RequestLoggingContext = RequestLoggingContext()

    println("=" * 60)
    println("Skuber Autoscaler Operator Example")
    println("=" * 60)

    val k8s = k8sInit

    // Configure the controller manager
    val managerConfig = ManagerConfig(
      namespace = None,  // Watch all namespaces
      leaderElection = None,
      cacheSyncTimeout = 30.seconds
    )

    val manager = ControllerManager(managerConfig, k8s)

    // Create the reconciler
    val reconciler = new AutoscalerReconciler()

    // Build the controller with Pod watching
    //
    // Namespace options for .watches:
    //   .watches[Pod]() { ... }                              // Use manager's namespace config (default)
    //   .watches[Pod](WatchNamespace.AllNamespaces) { ... }  // Watch all namespaces
    //   .watches[Pod](WatchNamespace.Specific("prod")) { ... } // Watch only "prod" namespace
    //
    // Convenience methods:
    //   .watchesAllNamespaces[Pod] { ... }      // Same as WatchNamespace.AllNamespaces
    //   .watchesInNamespace[Pod]("prod") { ... } // Same as WatchNamespace.Specific("prod")
    //
    val controller = ControllerBuilder[Autoscaler](manager)
      .withReconciler(reconciler)
      // Watch Pods in ALL namespaces and map them back to the owning Autoscaler
      .watchesAllNamespaces[Pod] { pod =>
        // Extract the Autoscaler name from Pod labels
        // This triggers reconciliation when any matching Pod changes
        pod.metadata.labels.get(OwnerLabel).map { autoscalerName =>
          NamespacedName(pod.metadata.namespace, autoscalerName)
        }
      }
      .withConcurrency(2)
      .build()

    manager.add(controller)

    println("Starting controller manager...")
    println(s"Watching Autoscaler resources and Pods with label '$OwnerLabel'")

    // Start the manager
    val startFuture = manager.start()

    startFuture.onComplete {
      case Success(_) =>
        println("Controller manager started successfully")
        println("Watching for Autoscaler resources...")
        println()
        println("To test, create an Autoscaler and some Pods:")
        println("""
          |  kubectl apply -f - <<EOF
          |  apiVersion: scaling.example.com/v1
          |  kind: Autoscaler
          |  metadata:
          |    name: my-autoscaler
          |  spec:
          |    selector:
          |      app: myapp
          |    minReplicas: 2
          |    maxReplicas: 10
          |  EOF
          |
          |  # Create a Pod that triggers reconciliation:
          |  kubectl run test-pod --image=nginx --labels="app=myapp,scaling.example.com/autoscaler=my-autoscaler"
          """.stripMargin)
        println()
        println("Press Ctrl+C to stop")

      case Failure(e) =>
        println(s"Failed to start controller manager: ${e.getMessage}")
        e.printStackTrace()
        system.terminate()
    }

    // Handle shutdown
    sys.addShutdownHook {
      println("\nShutting down...")
      Await.result(manager.stop(), 30.seconds)
      k8s.close()
      Await.result(system.terminate(), 5.seconds)
      println("Shutdown complete")
    }

    // Keep the main thread alive
    Await.result(system.whenTerminated, Duration.Inf)
