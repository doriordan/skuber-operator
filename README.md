# Skuber Operator

*This project is in an early, experimental phase.*

An SDK for building Kubernetes Operators in Scala.

The SDK enables developers to easily define Kubernetes custom resources as annotated case classes  - a simple example:
```scala
// Autoscaler is a simple custom resource type which could be used as the basis for an autoscaling operator
@experimental
@customResource(
  group = "test.skuber.io",
  version = "v1",
  kind = "Autoscaler",
  scope = Scope.Namespaced,
  statusSubresource = true
)
object Autoscaler extends CustomResourceDef[Autoscaler.Spec, Autoscaler.Status]:
  case class Spec(desiredReplicas: Int, image: String)
  case class Status(availableReplicas: Int, ready: Boolean)

// OPTIONAL type alias that slightly simplifies generic type parameters
type Autoscaler = Autoscaler.Resource
```
The `@customResource` macro generates all the code needed to use `Autoscaler` as a custom resource type, including creating, updating, retrieving, listing, removing and watching the resources on a cluster.

The SDK provides a framework to build controllers and operators based on these user-defined custom resources and the [Operator Pattern](https://kubernetes.io/docs/concepts/extend-kubernetes/operator/)

It broadly follows the well-understood and tested Kubernetes runtime controller/operator design as implemented by [kubebuilder](https://book.kubebuilder.io/introduction.html) amongst others, including:
- *controller*: manages the main control loop for a particular custom resource type, using the cache/reflector to receive updates and triggering reconciliation
- *reconciler*: application-defined logic for driving updates to managed resources based on updates to watched resources (reconciliation) 
- *reflector*: continually reflects the state of the watched resources into the local cache, by continually watching events on the resources complemented by periodic (re)syncing which refreshes the cache with a fresh list of the resources from the cluster.

It builds on the established [Skuber](https://github.com/doriordan/skuber) library for its core Kubernetes client functionality, including the list/watch funcionality of the reflector. Under the hood it uses Pekko streams for event handling, which manages backpressure, rate-limiting and so on.

## Quickstart

*Note* Using this SDK requires Scala 3.8+ and the `experimental` flag due to use of Scala 3 macro annotations. 

*Note* The status of this project is currently pre-release so to play around with building an operator, it is probably best to clone this repo and create a sub-rpoject locally for your use case, similar to the 'examples' subproject. The aim is to create an initial release quite soon and update docs accordingly.

The following is a very simple example of building an operator - an Autoscaler that maintains a specified number of replicas (pods or some other workload type).
The steps to create the operator are:

- define the Autoscaler custom resource type as above
- implement the reconciliation logic
```scala
val reconciler = new Reconciler[Autoscaler] {
      def reconcile(resource: Autoscaler, ctx: ReconcileContext[Autoscaler]): Future[ReconcileResult] = {
        val currentStatusReplicas = resource.status.map(_.availableReplicas).getOrElse(0)
        // use the skuber client (available on `ctx` parameter) to find out how many replicas are actually running in the taregt namespace
        ctx.client
          .usingNamespace("autoscaled_replica_ns")
          .list[PodList]()
          .map { // return list size }
          .flatMap { actualCurrentReplicas =>
            val desiredReplicas = resource.spec.desiredReplicas
  
            val updateStatusIfNecessary = if (actualCurrentReplicas != currentStatusReplicas) {
              // update Autoscaler status to reflect real count
              val currentStatus = kronJob.status.getOrElse(KronJobResource.Status())
              val newStatus = currentStatus.copy(availableReplicas = actualCurrentReplicas)
              val updated = kronJob.copy(status = Some(newStatus))
              ctx.client.usingNamespace(resource.metadata.namespace).updateStatus(updated)
            } else {
              Future.successful()  no status update needed
            }
            // now add or remove a new replica if desired != actual replicas
            val addOrRemoveReplicaIfNecessary = if (actualCurrentReplicas > desiredReplicas) {
              // use ctx.client to select a pod for deletion and delete it
            } else if (actualCurrentReplicas < desiredReplicas) {
              // use ctx.client together with the specified 'image' in the Autoscaler spec
              // to create a new replica (pod)
            } else {
              Future.successful()
            }
            for {
              _ <- updateStatusIfNecessary
              _ <- addOrRemoveReplicaIfNecessary
            } yield ReconcileResult.Done
          }
        }
      }
}  
```
- create and register the controller

```scala

val managerConfig = ... // this configures e.g. in which namespace (or all) the operators custom resources exist
val manager = ControllerManager(managerConfig, k8s)

val controller = ControllerBuilder[Autoscaler](manager)
    .withReconciler(reconciler)
    // places a secondary watch on Pods in ALL namespaces
    .watchesAllNamespaces[Pod] { pod =>
      pod.metadata.labels.get(OwnerLabel).map { autoscalerName =>
        NamespacedName(pod.metadata.namespace, autoscalerName)
      }
    }
    // OR watch Pods only in "production" namespace
    // .watchesInNamespace[Pod]("production") { pod => ... }
    .withConcurrency(1)
    .build()

manager.add(controller)
```
Note the `watches` method call(s) above - this ensures that the controller will not just watch the Autoscaler resources but also watch for any Pod status changes and then trigger reconciliation for the "owning" Autoscaler custom resource, if there is one for that pod - this is often necessary to ensure status changes of owned resources are detected and reconciled. Also you can see that you can place a secondary watch on pods in a *different* namespace to the Autoscaler one, or in *all* namespaces depending on your requirements.

See the [examples](examples/src/main/scala/skuber/examples/kronjob) subproject for a more complex operator that implements CronJob functionality for scheduling and controlling Kubernetes Jobs.

  
