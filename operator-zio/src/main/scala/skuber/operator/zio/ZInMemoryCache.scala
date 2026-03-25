package skuber.operator.zio

import zio.*
import play.api.libs.json.{Format, JsValue, Json}
import skuber.model.{ObjectResource, ResourceDefinition}
import skuber.operator.reconciler.NamespacedName

class ZInMemoryCache private (
  stateRef: Ref[Map[String, Map[NamespacedName, JsValue]]]
):
  // key used to bucket resources by kind
  private def kindKey[O <: ObjectResource](using rd: ResourceDefinition[O]): String =
    rd.spec.names.kind

  def get[O <: ObjectResource](name: NamespacedName)(
    using fmt: Format[O], rd: ResourceDefinition[O]
  ): UIO[Option[O]] =
    stateRef.get.map { state =>
      state.get(kindKey).flatMap(_.get(name)).flatMap { jsv =>
        Json.fromJson[O](jsv).asOpt
      }
    }

  def update[O <: ObjectResource](obj: O)(
    using fmt: Format[O], rd: ResourceDefinition[O]
  ): UIO[Unit] =
    val name = NamespacedName(obj.metadata.namespace, obj.metadata.name)
    val jsv  = Json.toJson(obj)
    stateRef.update { state =>
      val bucket = state.getOrElse(kindKey, Map.empty)
      state.updated(kindKey, bucket.updated(name, jsv))
    }

  def delete[O <: ObjectResource](name: NamespacedName)(
    using rd: ResourceDefinition[O]
  ): UIO[Unit] =
    stateRef.update { state =>
      state.get(kindKey).fold(state) { bucket =>
        state.updated(kindKey, bucket.removed(name))
      }
    }

  def list[O <: ObjectResource](
    using fmt: Format[O], rd: ResourceDefinition[O]
  ): UIO[List[O]] =
    stateRef.get.map { state =>
      state.getOrElse(kindKey, Map.empty).values.toList
        .flatMap(jsv => Json.fromJson[O](jsv).asOpt)
    }

object ZInMemoryCache:
  def make: UIO[ZInMemoryCache] =
    Ref.make(Map.empty[String, Map[NamespacedName, JsValue]])
      .map(new ZInMemoryCache(_))
