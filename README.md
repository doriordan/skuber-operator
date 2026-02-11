# Skuber Operator

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

```

The SDK provides a framework to build controllers and operators based on these user-defined custom resources and the [Operator Pattern](https://kubernetes.io/docs/concepts/extend-kubernetes/operator/)

It broadly follows the well-understood and tested Kubernetes runtime controller/operator design as exemplified by [kubebuilder](https://book.kubebuilder.io/introduction.html), supporting transparent local caching of resources, work queuing, reconciliation and finalizers.

It builds on the established [Skuber](https://github.com/doriordan/skuber) library for its core Kubernetes client functionality, including use  of Pekko streams under the covers to handle events for the controllers, including handling of backpressure, rate-limiting and so on.

See the `examples` sub-project for a moderately complex operator that implements CronJob functionality for scheduling and controlling Kubernetes Jobs.

*IMPORTANT* This project is in its early, experimental phase.

*Note* Requires Scala 3.8+ and `experimental` flag due to use of Scala 3 macro annotations.
  
