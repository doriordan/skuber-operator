package skuber.zio

import zio.*
import play.api.libs.json.Format
import skuber.model.{ObjectResource, ResourceDefinition, Namespace}

/**
 * ZIO-based Kubernetes client abstraction.
 * This is a placeholder interface for ZIO-native Kubernetes client operations.
 */
trait ZKubernetesClient:
  /**
   * Get a resource by namespace and name
   */
  def get[O <: ObjectResource](
    name: String,
    namespace: String = "default"
  )(using Format[O], ResourceDefinition[O]): IO[Throwable, O]

  /**
   * List resources in a namespace
   */
  def list[O <: ObjectResource](
    namespace: String = "default"
  )(using Format[O], ResourceDefinition[O]): IO[Throwable, List[O]]

  /**
   * Create a resource
   */
  def create[O <: ObjectResource](
    obj: O
  )(using Format[O], ResourceDefinition[O]): IO[Throwable, O]

  /**
   * Update a resource
   */
  def update[O <: ObjectResource](
    obj: O
  )(using Format[O], ResourceDefinition[O]): IO[Throwable, O]

  /**
   * Delete a resource
   */
  def delete[O <: ObjectResource](
    name: String,
    namespace: String = "default"
  )(using ResourceDefinition[O]): IO[Throwable, Unit]
