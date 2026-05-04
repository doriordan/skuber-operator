package skuber.examples.nogen

import scala.annotation.experimental
import skuber.operator.crd.{CustomResourceSpecDef, Scope, customResource, noApplyConfigGen}

@experimental
@noApplyConfigGen
@customResource(
  group = "test.skuber.io",
  version = "v1",
  kind = "NoGen",
  scope = Scope.Namespaced
)
object NoGenResource extends CustomResourceSpecDef[NoGenResource.Spec]:

  case class Spec(
    name: String,
    replicas: Int = 1
  )
