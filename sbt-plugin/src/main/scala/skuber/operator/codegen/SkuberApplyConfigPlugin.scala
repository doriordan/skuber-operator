package skuber.operator.codegen

import sbt._
import sbt.Keys._

object SkuberApplyConfigPlugin extends AutoPlugin {

  override def trigger = noTrigger
  override def requires = plugins.JvmPlugin

  override def projectSettings: Seq[Setting[_]] = Seq(
    Compile / sourceGenerators += Def.task {
      ApplyConfigGenerator.generate(
        (Compile / sourceDirectories).value,
        (Compile / sourceManaged).value
      )
    }.taskValue
  )
}
