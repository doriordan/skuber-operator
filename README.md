# Skuber Operator

*This project is at an early pre-release stage and is being constantly updated, so check back regularly for updates*

An SDK for building Kubernetes operators and controllers in Scala.

Key features:
- define boilerplate-free Kubernetes custom resources using Scala case classes and macro annotations
- create operators in Scala that support the [Operator Pattern](https://kubernetes.io/docs/concepts/extend-kubernetes/operator/) 
- the design of the controllers follows established best practices in the Kubernetes ecosystem
- application defines reconcilers that drive actual state of controlled resources towards desired state
- the controller takes care of monitoring the cluster and triggering reconciliation when required
- a reflector continually keeps a local cache of monitored resources in sync with cluster
- leverages the long established [Skuber](https://github.com/doriordan/skuber) library for underlying Kubernetes client functionality including event handling

## Custom Resources

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
The `@customResource` macro generates all the code needed to use `Autoscaler` as a custom resource type, including the JSON readers and writers that enable creating, updating, retrieving, listing, removing and watching resources of this kind on a cluster.
The annotated properties group, kind and version uniquely identify this custom resource type to the Kubernetes API, while the Spec and Status nested case classes define the content of the resources.

## Operator Basics: controllers, reconcilers, reflectors and caches

The SDK provides a framework to build controllers and operators based on these user-defined custom resources and the [Operator Pattern](https://kubernetes.io/docs/concepts/extend-kubernetes/operator/)

It broadly follows the well-understood and tested Kubernetes runtime controller/operator design as implemented by [kubebuilder](https://book.kubebuilder.io/introduction.html) amongst others, including:
- *controller*: manages the main control loop for a particular custom resource type, using the cache/reflector to receive updates and triggering reconciliation
- *reconciler*: application-defined logic for driving updates to managed resources based on updates to watched resources (reconciliation) 
- *cache and reflector*: the reflector continually syncs the state of the relevant resources on the cluster into a local cache, initially by listing then watching them for updates. This enables speedy reconciliation and significantly reduces cluster traffic.

It builds on the established [Skuber](https://github.com/doriordan/skuber) library for its core Kubernetes client functionality, including the list/watch functionality of the reflector. Under the hood it uses [Pekko](https://pekko.apache.org/) streams for event handling, with benefits for managing backpressure, rate-limiting and so on.

## Dependencies

Using this SDK requires Scala 3 - specifically at this time it requires Scala `3.8+` and use of the `experimental` compiler flag due to the use of Scala 3 macro annotations for defining custom resources.

The SDK has dependencies on Skuber (`3.1+`) and Pekko for the underlying Kubernetes client functionality, and relies on Play Json for custom resource JSON readers and writers.

## Quickstart

*The status of this project is currently pre-release so to play around with building an operator, it is probably best to clone this repo and create a subproject locally for your use case, similar to the 'examples' subproject. The aim is to create an initial release quite soon and update docs accordingly.*

The following is a simplified example of building an operator (more a controller really) - an Autoscaler that maintains a specified number of replicas that a user can scale up or down by simply changing the spec on the custom resource, so it operates really as a simplified replica set controller.
The steps to create the operator are:

#### Step 1: Define the Autoscaler custom resource type 

See the example above.

This tells your controller everything it needs to know about the custom resource type, but you will also need to define a corresponding [CRD](https://kubernetes.io/docs/concepts/extend-kubernetes/api-extension/custom-resources/#customresourcedefinitions) that describes the same custom resource type to Kubernetes itself. This can be done manually on the cluster or programmatically - see the `AutoscalerCRDFixture` in the integration tests for an example of the latter.

#### Step 2: Build the controller

```scala

val reconciler: Reconciler[Autoscaler] = ??? // see Step 3

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
```
This controller will monitor the Autoscaler custom resources (the *primary* resources) as well as any pods in "groupOneReplicas" and "groupTwoReplicas" namespaces (the *secondary* resources), invoking the reconciler if the state of those resources changes.

This ensure reconciliation is called for any change in the spec of the Autoscaler (for example desired replica count) but also any change in the owned resources (for example a pod is evicted from a node).

The `toOwner` function tells the controller which specific Autoscaler resource (there can be multiple Autoscalers) owns any changed pod, so that the controller can pass it to the reconciler.

You can place a watch in specific namespaces as above, or in *all* namespaces (using `watchInAllNamespaces`) depending on your requirements.

#### Step 3: Implement the reconciliation logic

The reconciler is the application logic that converges the state of the resources on the cluster with desired state - so in the case of the Autoscaler operator we want to converge the actual number of controlled replicas to the desired replica count in the spec.

```scala
val reconciler = new Reconciler[Autoscaler] {
      def reconcile(autoscaler: Autoscaler, ctx: ReconcileContext[Autoscaler]): Future[ReconcileResult] = {
        // get an event recorder that can be used to publish controller events to Kubernetes that can
        // later be examined using e.g. `kubectl events`
        val recorder = ctx.eventRecorder
        val currentStatusReplicas = autoscaler.status.map(_.availableReplicas).getOrElse(0)
        
        val autoscalerId = NamespacedName(autoscaler.namespace, autoscaler.name)
        val OwnerLabel = "owner-autoscaler"
        // retrieve the locally cached pod list and count the available replicas
        val ownedReplicas = ctx
          .listCachedInNamespace[Pod](autoscaler.spec.targetNamespace)
          .filter(_.metadata.labels.get(OwnerLabel).contains(autoscalerId.toString))
        val isAvailable p: Pod => ... // return true if pod phase is running or pending (is available or expected to soon be available)
        val actualAvailableReplicas = ownedReplicas.filter(isAvailable).size
        
        val desiredReplicas = autoscaler.spec.desiredReplicas
        // get the underlying Skuber client for updating managed resources if necessary
        val k8s = ctx.client
        val updateStatusIfNecessary = if (actualAvailableReplicas != currentStatusReplicas) {
          // update Autoscaler status to reflect real count
          val currentStatus = autoscaler.status.getOrElse(Autoscaler.Status())
          val newStatus = currentStatus.copy(availableReplicas = actualAvailableReplicas)
          val updated = autoscaler.copy(status = Some(newStatus))
          k8s.usingNamespace(autoscaler.metadata.namespace).updateStatus(updated)
        } else {
          Future.successful()  // no status update needed
        }
        // now add or remove a new replica if desired != actual replicas - this will gradually 
        // converge the actual to the desired state as pods coming up and down on the cluster 
        // trigger further reconciliations
        val addOrRemoveReplicaIfNecessary = if (actualAvailableReplicas > desiredReplicas) {
          // select a pod for deletion and delete it
          val selectedPodName = ...
          k8s.usingNamespace(autoscaler.spec.targetNamespace)
            .delete(selectedPodName)
            .andThen { _ => recorder.normal("REPLICA_DELETED", selectedPodName) }
        } else if (actualAvailableReplicas < desiredReplicas) {
          // build a new replica (pod) 
          val newReplica: Pod = buildReplica(autoscaler.spec.targetNamespace, autoscaler.spec.image)
            .addLabel(OwnerLabel -> autoscalerId))
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
```
The above reconciler carries out these basic steps:
- first check if the Autoscaler status reflects actual status (replica count) on the cluster, updating if not. Because the managed replicas are watched by the controller (see Step 3), the current pod list is in the local cache so it does not have to fetch the replica list from the cluster each time.
- next check if the actual replica count is the same as the desired replica count - if not, it either creates or deletes a replica (pod) as required
- it also produces events which Kubernetes stores and returns to users when requested.

#### Step 4: Register and start the contoller.

```scala

manager.add(controller)

val startFuture = manager.start()
```
### Primary, Secondary and Owned Resources

Kubernetes controllers commonly manage *Primary Resources* and *Secondary Resources*. A Primary Resource is the main resource that the controller is responsible for, while Secondary Resources are created and managed by the controller to support the Primary Resource.

In the Autoscaler case the Autoscaler custom resources are the primary resources, while the pods (replicas) that it manages are the secondary resources. 

The controller must know what the secondary resources are so that it can watch them and trigger reconciliation if their status changes. For this purpose it also needs to know which primary resource "owns" the impacted secondary resources so it can pass it to the reconciler.

In the case of the Autoscaler example the `isOwner` user-defined function identifies the owner (as a namespaced name e.g. "default/autoscaler1") from a label on the resources. It is the responsibility of the application to ensure created secondary resources have the correct label applied:
```scala
  val autoscalerId = NamespacedName(autoscaler.namespace, autoscaler.name)
  val OwnerLabel = "owner-autoscaler"
  val newReplica: Pod = buildReplica(autoscaler.spec.targetNamespace, autoscaler.spec.image)
      .addLabel(OwnerLabel -> autoscalerId))
```

A simpler alternative exists if your controller has an explicit ownership relation using [owner references](https://kubernetes.io/docs/concepts/overview/working-with-objects/owners-dependents/#owner-references-in-object-specifications) with the secondary resources it controls - in this case, instead of specifying some `watch...` command you can simply use the `owns` method without needing to provide a function that knows how to get the owner.

In the KronJob example in the [examples](examples/src/main/scala/skuber/examples/kronjob) each job has an owner reference to the KronJob resource that creates and controls it, so we can just use `owns` to watch them: 

```scala
val controller = ControllerBuilder[KronJob](manager)
    .withReconciler(reconciler)
    .owns[Job]
    .withConcurrency(1)
    .build()
```
When any job changes (or is deleted) the controller identifies the owner KronJob from the owner reference(s) on the job and passes it the reconciler.

Again it is the responsibility of the application logic to apply the correct owner reference to any created job - in the KronJob example this is handled when building the Job as follows:
```scala
Job(
      metadata = ObjectMeta(
        name = jobName,
        namespace = kronJob.metadata.namespace,
        annotations = Map(ScheduledTimeAnnotation -> scheduledTime.toString),
        ownerReferences = List(OwnerReference(
          apiVersion = kronJob.apiVersion,
          kind = kronJob.kind,
          name = kronJob.name,
          uid = kronJob.metadata.uid,
          controller = Some(true),
          blockOwnerDeletion = Some(true)
        ))
      ),
      spec = Some(jobSpec)
    )
```

There are some additional benefits of using owner references - for example the above will cause Kubernetes to prevent the owner primary resource KronJob being deleted as long as jobs being managed by it exist, which can be useful to maintain consistency.

When owner references are used the owner is constrained to be in the same namespace as the owned resource or be cluster-scoped so we don't need the same namespace flexibility as the more generic `watchInNamespace` controller method.

See [Owners And Dependents](https://kubernetes.io/docs/concepts/overview/working-with-objects/owners-dependents/) for more details that can help you decide whether to use owner references in your controllers.
  
