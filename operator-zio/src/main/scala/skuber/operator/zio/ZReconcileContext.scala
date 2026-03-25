package skuber.operator.zio

import zio.*
import play.api.libs.json.Format
import skuber.model.{ObjectResource, ResourceDefinition}
import skuber.operator.reconciler.NamespacedName
import skuber.zio.ZKubernetesClient

trait ZReconcileContext:
  val client: ZKubernetesClient
  def getFromCache[O <: ObjectResource](name: NamespacedName)(
    using Format[O], ResourceDefinition[O]
  ): UIO[Option[O]]
