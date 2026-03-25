package skuber.operator.zio

import zio.*
import skuber.api.client.K8SException
import skuber.model.ObjectResource
import skuber.operator.reconciler.ReconcileResult

trait ZReconciler[R <: ObjectResource]:
  def reconcile(resource: R, ctx: ZReconcileContext): IO[K8SException, ReconcileResult]
