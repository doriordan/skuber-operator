package skuber.operator.controller

import play.api.libs.json.Format
import skuber.model.{ObjectResource, ResourceDefinition}
import skuber.operator.reconciler.{NamespacedName, Reconciler}

/**
 * Builder for constructing controllers with a fluent API.
 *
 * Usage:
 * {{{
 * val controller = ControllerBuilder[MyResource](manager)
 *   .withReconciler(new MyReconciler)
 *   .owns[Deployment]
 *   .owns[Service]
 *   .withConcurrency(4)
 *   .build()
 * }}}
 *
 * @tparam R The primary resource type
 */
class ControllerBuilder[R <: ObjectResource](
  manager: ControllerManager
)(using rd: ResourceDefinition[R], fmt: Format[R]):

  private var _reconciler: Option[Reconciler[R]] = None
  private var _owns: List[OwnedResource[?]] = Nil
  private var _watches: List[WatchedResource[?]] = Nil
  private var _concurrency: Int = 1
  private var _workQueueConfig: WorkQueueConfig = WorkQueueConfig.default

  /**
   * Set the reconciler for this controller.
   * Required before calling build().
   */
  def withReconciler(r: Reconciler[R]): this.type =
    _reconciler = Some(r)
    this

  /**
   * Watch resources owned by R (via OwnerReference).
   * Changes to owned resources trigger reconciliation of the owner.
   *
   * @tparam O The owned resource type (e.g., Deployment, Service)
   */
  def owns[O <: ObjectResource](using ord: ResourceDefinition[O], ofmt: Format[O]): this.type =
    _owns = OwnedResource[O](ord, ofmt) :: _owns
    this

  /**
   * Watch related resources with custom key extraction.
   * The mapper returns the NamespacedName of the R to reconcile.
   *
   * @tparam O The related resource type
   * @param namespace Namespace scope for watching (default: use manager's config)
   * @param mapper Function to extract the owning R's key from O
   */
  def watches[O <: ObjectResource](
    namespace: WatchNamespace = WatchNamespace.ManagerDefault
  )(mapper: O => Option[NamespacedName])(
    using ord: ResourceDefinition[O], ofmt: Format[O]
  ): this.type =
    _watches = WatchedResource[O](ord, ofmt, mapper, namespace) :: _watches
    this

  /**
   * Watch related resources in all namespaces with custom key extraction.
   * Convenience method equivalent to watches(WatchNamespace.AllNamespaces)(mapper).
   *
   * @tparam O The related resource type
   * @param mapper Function to extract the owning R's key from O
   */
  def watchesAllNamespaces[O <: ObjectResource](mapper: O => Option[NamespacedName])(
    using ord: ResourceDefinition[O], ofmt: Format[O]
  ): this.type =
    watches(WatchNamespace.AllNamespaces)(mapper)

  /**
   * Watch related resources in a specific namespace with custom key extraction.
   * Convenience method equivalent to watches(WatchNamespace.Specific(ns))(mapper).
   *
   * @tparam O The related resource type
   * @param namespace The namespace to watch
   * @param mapper Function to extract the owning R's key from O
   */
  def watchesInNamespace[O <: ObjectResource](namespace: String)(mapper: O => Option[NamespacedName])(
    using ord: ResourceDefinition[O], ofmt: Format[O]
  ): this.type =
    watches(WatchNamespace.Specific(namespace))(mapper)

  /**
   * Set maximum concurrent reconciliations.
   * Default is 1 (sequential).
   */
  def withConcurrency(n: Int): this.type =
    require(n > 0, "Concurrency must be positive")
    _concurrency = n
    this

  /**
   * Set custom work queue configuration.
   */
  def withWorkQueueConfig(config: WorkQueueConfig): this.type =
    _workQueueConfig = config
    this

  /**
   * Build the controller.
   *
   * @throws IllegalStateException if reconciler is not set
   */
  def build(): Controller[R] =
    require(_reconciler.isDefined, "Reconciler is required. Call withReconciler() before build().")

    new PekkoController[R](
      manager = manager,
      reconciler = _reconciler.get,
      owns = _owns,
      watches = _watches,
      concurrency = _concurrency,
      workQueueConfig = _workQueueConfig
    )(using rd, fmt, manager.actorSystem)

/**
 * Describes an owned resource type.
 */
private[controller] case class OwnedResource[O <: ObjectResource](
  rd: ResourceDefinition[O],
  fmt: Format[O]
)

/**
 * Describes a watched resource type with custom mapping and namespace scope.
 */
private[controller] case class WatchedResource[O <: ObjectResource](
  rd: ResourceDefinition[O],
  fmt: Format[O],
  mapper: O => Option[NamespacedName],
  namespace: WatchNamespace
)
