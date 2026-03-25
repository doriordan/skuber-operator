package skuber.operator

import play.api.libs.json.*
import skuber.model.ResourceDefinition

package object zio:
  case class TestSpec(value: String)

  case class TestResource(
    override val apiVersion: String = "test/v1",
    override val kind: String = "TestResource",
    override val metadata: skuber.model.ObjectMeta,
    spec: TestSpec
  ) extends skuber.model.ObjectResource

  given OFormat[TestSpec] with
    def reads(json: JsValue): JsResult[TestSpec] =
      (json \ "value").validate[String].map(TestSpec(_))
    def writes(o: TestSpec): JsObject =
      Json.obj("value" -> o.value)

  given Format[TestResource] with
    def reads(json: JsValue): JsResult[TestResource] =
      // Simple parsing: just store metadata as name/namespace pair
      for
        apiVersion <- (json \ "apiVersion").validateOpt[String].map(_.getOrElse("test/v1"))
        kind <- (json \ "kind").validateOpt[String].map(_.getOrElse("TestResource"))
        ns <- (json \ "metadata" \ "namespace").validateOpt[String]
        nm <- (json \ "metadata" \ "name").validate[String]
        spec <- (json \ "spec").validate[TestSpec]
      yield
        val metadata = skuber.model.ObjectMeta(namespace = ns.getOrElse(""), name = nm)
        TestResource(apiVersion, kind, metadata, spec)
    def writes(o: TestResource): JsValue =
      Json.obj(
        "apiVersion" -> o.apiVersion,
        "kind" -> o.kind,
        "metadata" -> Json.obj(
          "namespace" -> o.metadata.namespace,
          "name" -> o.metadata.name
        ),
        "spec" -> o.spec
      )

  case class TestSpec2(count: Int)

  case class TestResource2(
    override val apiVersion: String = "test2/v1",
    override val kind: String = "TestResource2",
    override val metadata: skuber.model.ObjectMeta,
    spec: TestSpec2
  ) extends skuber.model.ObjectResource

  given OFormat[TestSpec2] with
    def reads(json: JsValue): JsResult[TestSpec2] =
      (json \ "count").validate[Int].map(TestSpec2(_))
    def writes(o: TestSpec2): JsObject =
      Json.obj("count" -> o.count)

  given Format[TestResource2] with
    def reads(json: JsValue): JsResult[TestResource2] =
      for
        apiVersion <- (json \ "apiVersion").validateOpt[String].map(_.getOrElse("test2/v1"))
        kind <- (json \ "kind").validateOpt[String].map(_.getOrElse("TestResource2"))
        ns <- (json \ "metadata" \ "namespace").validateOpt[String]
        nm <- (json \ "metadata" \ "name").validate[String]
        spec <- (json \ "spec").validate[TestSpec2]
      yield
        val metadata = skuber.model.ObjectMeta(namespace = ns.getOrElse(""), name = nm)
        TestResource2(apiVersion, kind, metadata, spec)
    def writes(o: TestResource2): JsValue =
      Json.obj(
        "apiVersion" -> o.apiVersion,
        "kind" -> o.kind,
        "metadata" -> Json.obj(
          "namespace" -> o.metadata.namespace,
          "name" -> o.metadata.name
        ),
        "spec" -> o.spec
      )

  given ResourceDefinition[TestResource2] = ResourceDefinition[TestResource2](
    kind = "TestResource2", group = "test2", version = "v1"
  )
