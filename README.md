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
  case class Spec(targetNamespace: String, desiredReplicas: Int, image: String)
  case class Status(availableReplicas: Int, ready: Boolean)

// OPTIONAL type alias that slightly simplifies generic type parameters
type Autoscaler = Autoscaler.Resource
```
The `@customResource` macro generates all the code needed to use `Autoscaler` as a custom resource type, including creating, updating, retrieving, listing, removing and watching the resources on a cluster.
The annotated properties group, kind and version uniquely identify this custom resource type to the Kubernetes API, while the Spec and Status nested case classes define the content of the resources.

The SDK provides a framework to build controllers and operators based on these user-defined custom resources and the [Operator Pattern](https://kubernetes.io/docs/concepts/extend-kubernetes/operator/)

It broadly follows the well-understood and tested Kubernetes runtime controller/operator design as implemented by [kubebuilder](https://book.kubebuilder.io/introduction.html) amongst others, including:
- *controller*: manages the main control loop for a particular custom resource type, using the cache/reflector to receive updates and triggering reconciliation
- *reconciler*: application-defined logic for driving updates to managed resources based on updates to watched resources (reconciliation) 
- *cache and reflector*: the reflector continually reflects the state of the watched resources into a local cache, by initially retrieving all resources from the cluster and storing them in the cache and then continually watching events for further updates on the cached resources.

It builds on the established [Skuber](https://github.com/doriordan/skuber) library for its core Kubernetes client functionality, including the list/watch functionality of the reflector. Under the hood it uses [Pekko](https://pekko.apache.org/) streams for event handling, with benefits for managing backpressure, rate-limiting and so on.

## Quickstart

*Note* Using this SDK requires Scala 3.8+ and the `experimental` flag due to use of Scala 3 macro annotations. 

*Note* The status of this project is currently pre-release so to play around with building an operator, it is probably best to clone this repo and create a subproject locally for your use case, similar to the 'examples' subproject. The aim is to create an initial release quite soon and update docs accordingly.

The following is a very simple example of building an operator (more a controller really) - an Autoscaler that maintains a specified number of replicas (in this case pods) that a user can scale up or down by simply changing the spec on the custom resource.
The steps to create the operator are:

Step 1: Define the Autoscaler custom resource type as above. 

(This tells your controller everything it needs to know about the custom resource type, but you will also need to define a corresponding [CRD](https://kubernetes.io/docs/concepts/extend-kubernetes/api-extension/custom-resources/#customresourcedefinitions) that describes the same custom resource type to Kubernetes itself - this can be done manually on the cluster or programmatically (see the `AutoscalerCRDFixture` in the integration tests for an example of the latter)

Step 2: Implement the reconciliation logic as below:
```scala
val reconciler = new Reconciler[Autoscaler] {
      def reconcile(autoscaler: Autoscaler, ctx: ReconcileContext[Autoscaler]): Future[ReconcileResult] = {
        // get an event recorder that can be used to publish controller events to Kubernetes that can
        // later be examined using e.g. `kubectl events`
        val recorder = ctx.eventRecorder
        // get the underlying Skuber client for accessing managed resources
        val k8s = ctx.client
        val currentStatusReplicas = autoscaler.status.map(_.availableReplicas).getOrElse(0)
        k8s.usingNamespace(autoscaler.spec.targetNamespace)
          .list[PodList]()
          .map { // ...  count pods in the returned pod list that are either Pending or Running as available }
          .flatMap { pendingOrRunningReplicas =>
            val desiredReplicas = autoscaler.spec.desiredReplicas
  
            val updateStatusIfNecessary = if (pendingOrRunningReplicas != currentStatusReplicas) {
              // update Autoscaler status to reflect real count
              val currentStatus = autoscaler.status.getOrElse(Autoscaler.Status())
              val newStatus = currentStatus.copy(availableReplicas = pendingOrRunningReplicas)
              val updated = autoscaler.copy(status = Some(newStatus))
              k8s.usingNamespace(autoscaler.metadata.namespace)
                .updateStatus(updated)
            } else {
              Future.successful()  // no status update needed
            }
            // now add or remove a new replica if desired != actual replicas
            val addOrRemoveReplicaIfNecessary = if (actualCurrentReplicas > desiredReplicas) {
              // select a pod for deletion and delete it
              val selectedPodName = ...
              k8s.usingNamespace(autoscaler.spec.targetNamespace)
                .delete(selectedPodName)
                .andThen { _ => recorder.normal("REPLICA_DELETED", selectedPodName) }
            } else if (actualCurrentReplicas < desiredReplicas) {
              // build a new replica (pod) 
              val newReplica: Pod = buildReplica(autoscaler.spec.targetNamespace, autoscaler.spec.image)
                .addLabel(OwnerLabel -> NamespacedName(autoscaler.namespace, autoscaler.name))
              k8s.usingNamespace(autoscaler.spec.targetNamespace)
                .create(newReplica)
                .andThen { _ => recorder.normal("REPLICA_CREATED", newReplica.name) }
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
The above reconciler carries out some simple steps to drive the actual state of the resources it controls to the desired state as specified in the Autoscaler resource spec:
- first check if the Autoscaler status reflects actual status (replica count) on the cluster, updating if not
- next check if the actual replica count is the same as the desired replica count - if not, it either creates or deletes a replica (pod) as required
- it also produces events which Kubernetes stores and returns to users when requested.

Step 3: Create and start a controller that uses the above reconciler.

```scala

val managerConfig = ... // this configures e.g. in which namespace (or all) the operators custom resources exist
val manager = ControllerManager(managerConfig, k8s)

val OwnerLabel = "..."
val toOwner = pod: Pod => pod.metadata.labels.get(OwnerLabel).map(NamespacedName.fromString)

val controller = ControllerBuilder[Autoscaler](manager)
    .withReconciler(reconciler)
    .watchesInNamespace[Pod]("groupOneReplicas", toOwner)
    .watchesInNamespace[Pod]("groupTwoReplicas", toOwner)
    .withConcurrency(1)
    .build()

manager.add(controller)

val startFuture = manager.start()
```
Note the `watchesInNamespaces` method calls above. This ensures that the controller will not just watch the Autoscaler resources but also watch for any changes to "owned" resources (pods in this case) in the specified namespaces. 

The function passed to the watch method identifies the owner custom resource (as an optional `NamespacedName`) from the watched resource, which allows the controller to fetch that specific owner resource from the cache and pass it to to the reconciler, which can then drive any updates needed based on the changes to the owned resources.

In this case this ensures that if (for example) some pods are lost or fail due to a cluster node failing or the pod being evicted due to memory pressure, the reconciler will be invoked with the appropriate Autoscaler resource and can bring up replacement pods.

In general watching owned/controlled resources is usually necessary to ensure any changes in their status are detected and reconciled. 

You can place a watch on relevant resources in the *same* namespace or a *different* namespace to the Autoscaler one, or in *all* namespaces (using `watchInAllNamespaces`) depending on your requirements.

A simpler alternative exists if your controller has an explicit ownership relation using [owner references](https://kubernetes.io/docs/concepts/overview/working-with-objects/owners-dependents/#owner-references-in-object-specifications) with the resources it controls - in this case you can simply use the `owns` method without needing to provide a function that knows how to get the owner `NamespaceName` from the owned resource:

```scala
val controller = ControllerBuilder[KronJob](manager)
    .withReconciler(reconciler)
    .owns[Job]
    .withConcurrency(1)
    .build()
```
In the case of owner references, the owner is constrained to be in the same namespace as the owned resource so we don't need the same namespace flexibility as the more generic watch methods.

See the [examples](examples/src/main/scala/skuber/examples/kronjob) subproject for a more complex operator that implements CronJob functionality for scheduling and controlling Kubernetes Jobs.

  
