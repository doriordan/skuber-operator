package skuber.operator.zio

import zio.*
import zio.stream.*
import skuber.api.client.K8SException
import skuber.model.{ObjectResource, ResourceDefinition}
import skuber.operator.reconciler.{NamespacedName, ReconcileResult}
import skuber.zio.ZKubernetesClient
import play.api.libs.json.Format

case class ControllerConfig(workerCount: Int = 4)

class ZController[R <: ObjectResource : Format : ResourceDefinition](
  reconciler: ZReconciler[R],
  client: ZKubernetesClient,
  cache: ZInMemoryCache,
  queue: ZWorkQueue,
  config: ControllerConfig = ControllerConfig()
):
  private val ctx: ZReconcileContext = new ZReconcileContext:
    val client: ZKubernetesClient = ZController.this.client
    def getFromCache[O <: ObjectResource](name: NamespacedName)(
      using play.api.libs.json.Format[O], ResourceDefinition[O]
    ): UIO[Option[O]] = cache.get[O](name)

  private val initialBackoff: zio.Duration = 1.second

  private def reconcileKey(name: NamespacedName): UIO[Unit] =
    cache.get[R](name).flatMap {
      case None =>
        ZIO.logDebug(s"Resource $name not in cache (deleted), skipping")
      case Some(resource) =>
        reconciler.reconcile(resource, ctx).flatMap {
          case ReconcileResult.Done =>
            ZIO.unit
          case ReconcileResult.Requeue(_) =>
            queue.enqueue(name)
          case ReconcileResult.RequeueAfter(delay, _) =>
            ZIO.sleep(zio.Duration.fromScala(delay)) *> queue.enqueue(name)
        }.catchAll { (err: K8SException) =>
          ZIO.logError(s"Reconcile failed for $name: ${err.status.message.getOrElse("unknown")}") *>
          ZIO.sleep(initialBackoff) *>
          queue.enqueue(name)
        }.catchAllDefect { err =>
          ZIO.logError(s"Unexpected error reconciling $name: $err") *>
          ZIO.sleep(initialBackoff) *>
          queue.enqueue(name)
        }
    }

  /** Run the controller forever. Fails if either the reflector or worker fiber fails. */
  def run: ZIO[Any, Throwable, Nothing] =
    // Both effects are typed as Nothing so raceFirst is type-safe — no cast needed.
    val reflectorEffect: ZIO[Any, Throwable, Nothing] =
      ZReflector.stream[R](client, cache, queue)
        .runDrain
        .tapError(e => ZIO.logError(s"Reflector failed: $e"))
        *> ZIO.never

    val workerEffect: ZIO[Any, Throwable, Nothing] =
      ZStream.repeatZIO(queue.dequeue)
        .mapZIOPar(config.workerCount)(reconcileKey)
        .runDrain *> ZIO.never

    reflectorEffect raceFirst workerEffect
