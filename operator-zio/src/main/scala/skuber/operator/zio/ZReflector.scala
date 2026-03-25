package skuber.operator.zio

import zio.*
import zio.stream.*
import play.api.libs.json.Format
import skuber.api.client.{EventType, K8SException, WatchParameters}
import skuber.model.{ListResource, ObjectResource, ResourceDefinition}
import skuber.operator.reconciler.NamespacedName
import skuber.zio.ZKubernetesClient
import skuber.json.format.ListResourceFormat

object ZReflector:

  /** List+Watch stream that keeps `cache` in sync and enqueues changed names into `queue`.
   *  Starts watch from the list's resourceVersion to avoid the list/watch gap.
   *  Returns a stream that completes when the watch stream completes (client handles reconnects).
   */
  def stream[O <: ObjectResource : Format : ResourceDefinition](
    client: ZKubernetesClient,
    cache: ZInMemoryCache,
    queue: ZWorkQueue
  ): ZStream[Any, K8SException, NamespacedName] =
    given Format[ListResource[O]] = ListResourceFormat[O]

    ZStream.fromZIO(
      client.list[ListResource[O]]().tap { listed =>
        ZIO.foreach(listed.items)(cache.update[O])
          .zipRight(ZIO.logInfo(s"Listed ${listed.items.size} resources, rv=${listed.resourceVersion}"))
      }.map(_.resourceVersion)
    ).flatMap { rv =>
      client.watch[O](WatchParameters(resourceVersion = Some(rv)))
        .collect {
          // BOOKMARK and ERROR events are not resource changes; drop them entirely
          // to avoid spurious reconciliations.
          case event if event._type == EventType.ADDED ||
                        event._type == EventType.MODIFIED ||
                        event._type == EventType.DELETED => event
        }
        .mapZIO { event =>
          val obj  = event._object
          val name = NamespacedName(obj.metadata.namespace, obj.metadata.name)
          event._type match
            case EventType.ADDED | EventType.MODIFIED =>
              cache.update(obj).as(name)
            case _ => // DELETED
              cache.delete[O](name).as(name)
        }
        .tap(queue.enqueue)
    }
