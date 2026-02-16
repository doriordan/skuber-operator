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
  enum WatchNamespaces:
    /** Use the same namespace configuration as the ControllerManager */
    case ManagerDefault

    /** Watch resources in all namespaces, regardless of manager config */
    case AllNamespaces

    /** Watch resources only in the specified namespaces */
    case Specific(namespaces: List[String])

  object WatchNamespaces:
    /** Convenience for watching a single specific namespace */
    def apply(namespace: String): WatchNamespaces = Specific(List(namespace))

    /** Convenience for watching multiple pecific namespaces */
    def apply(namespaces: List[String]): WatchNamespaces = Specific(namespaces)

