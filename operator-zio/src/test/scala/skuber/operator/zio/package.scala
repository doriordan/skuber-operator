package skuber.operator

import play.api.libs.json.*

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
        nm <- (json \ "metadata" \ "name").validateOpt[String]
        spec <- (json \ "spec").validate[TestSpec]
      yield
        val metadata = skuber.model.ObjectMeta(namespace = ns.getOrElse(""), name = nm.getOrElse(""))
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
