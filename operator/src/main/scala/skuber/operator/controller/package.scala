package skuber.operator

/**
 * Controller infrastructure for the operator framework.
 *
 * The controller pattern follows kubebuilder/controller-runtime:
 * - One [[Controller]] per CRD (resource kind)
 * - [[ControllerManager]] orchestrates multiple controllers
 * - [[ControllerBuilder]] provides fluent API for construction
 *
 * Controllers watch resources, deduplicate events, and call reconcilers.
 */
package object controller:

  /**
   * Specifies the namespace scope for watching secondary resources.
   */
  enum WatchNamespace:
    /** Use the same namespace configuration as the ControllerManager */
    case ManagerDefault

    /** Watch resources in all namespaces, regardless of manager config */
    case AllNamespaces

    /** Watch resources only in the specified namespace */
    case Specific(namespace: String)

  object WatchNamespace:
    /** Convenience for watching a specific namespace */
    def apply(namespace: String): WatchNamespace = Specific(namespace)
