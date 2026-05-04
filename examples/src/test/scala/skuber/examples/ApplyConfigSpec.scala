package skuber.examples

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{Json, JsObject}

class ApplyConfigSpec extends AnyFlatSpec with Matchers:

  // ==================== Autoscaler (flat fields) ====================

  "AutoscalerApplyConfig" should "serialize only specified fields for SSA" in {
    import skuber.examples.autoscaler.AutoscalerResourceApplyConfigs.*

    val ac = AutoscalerApplyConfig("my-autoscaler")
      .withSpec(SpecApplyConfig().withMinReplicas(2).withMaxReplicas(8))

    val json = Json.toJson(ac)

    (json \ "kind").as[String] shouldBe "Autoscaler"
    (json \ "apiVersion").as[String] shouldBe "scaling.example.com/v1"
    (json \ "metadata" \ "name").as[String] shouldBe "my-autoscaler"
    (json \ "spec" \ "minReplicas").as[Int] shouldBe 2
    (json \ "spec" \ "maxReplicas").as[Int] shouldBe 8
    // Omitted fields should not be present
    (json \ "spec" \ "selector").toOption shouldBe None
    (json \ "spec" \ "targetCPUUtilization").toOption shouldBe None
    (json \ "status").toOption shouldBe None
  }

  it should "support labels and annotations" in {
    import skuber.examples.autoscaler.AutoscalerResourceApplyConfigs.*

    val ac = AutoscalerApplyConfig("my-autoscaler")
      .addLabel("app" -> "demo")
      .addAnnotation("managed-by" -> "controller")

    val json = Json.toJson(ac)

    (json \ "metadata" \ "labels" \ "app").as[String] shouldBe "demo"
    (json \ "metadata" \ "annotations" \ "managed-by").as[String] shouldBe "controller"
  }

  it should "support status apply config" in {
    import skuber.examples.autoscaler.AutoscalerResourceApplyConfigs.*

    val ac = AutoscalerApplyConfig("my-autoscaler")
      .withStatus(StatusApplyConfig().withCurrentReplicas(3).withMessage("Running"))

    val json = Json.toJson(ac)

    (json \ "status" \ "currentReplicas").as[Int] shouldBe 3
    (json \ "status" \ "message").as[String] shouldBe "Running"
    // Omitted status fields should not be present
    (json \ "status" \ "desiredReplicas").toOption shouldBe None
    (json \ "status" \ "lastObservedCPU").toOption shouldBe None
  }

  // ==================== KronJob (nested types + enum) ====================

  "KronJobApplyConfig" should "serialize nested case class apply config" in {
    import skuber.examples.kronjob.KronJobResourceApplyConfigs.*

    val ac = KronJobApplyConfig("my-kronjob")
      .withSpec(SpecApplyConfig()
        .withSchedule("0 * * * *")
        .withJobTemplate(JobTemplateSpecApplyConfig()
          .withImage("nginx")
          .withCommand(List("nginx", "-g", "daemon off;"))
        )
      )

    val json = Json.toJson(ac)

    (json \ "kind").as[String] shouldBe "KronJob"
    (json \ "spec" \ "schedule").as[String] shouldBe "0 * * * *"
    (json \ "spec" \ "jobTemplate" \ "image").as[String] shouldBe "nginx"
    (json \ "spec" \ "jobTemplate" \ "command").as[List[String]] shouldBe List("nginx", "-g", "daemon off;")
    // Omitted nested fields should not appear
    (json \ "spec" \ "jobTemplate" \ "args").toOption shouldBe None
    (json \ "spec" \ "jobTemplate" \ "restartPolicy").toOption shouldBe None
  }

  it should "serialize enum fields as strings" in {
    import skuber.examples.kronjob.KronJobResource
    import skuber.examples.kronjob.KronJobResourceApplyConfigs.*

    val ac = KronJobApplyConfig("my-kronjob")
      .withSpec(SpecApplyConfig()
        .withSchedule("0 * * * *")
        .withConcurrencyPolicy(KronJobResource.ConcurrencyPolicy.Forbid)
      )

    val json = Json.toJson(ac)

    (json \ "spec" \ "concurrencyPolicy").as[String] shouldBe "Forbid"
    // Only specified fields present
    (json \ "spec" \ "suspend").toOption shouldBe None
    (json \ "spec" \ "jobTemplate").toOption shouldBe None
  }

  it should "omit all unset fields from nested objects" in {
    import skuber.examples.kronjob.KronJobResourceApplyConfigs.*

    val ac = KronJobApplyConfig("my-kronjob")
      .withSpec(SpecApplyConfig().withSchedule("0 0 * * *"))

    val json = Json.toJson(ac)
    val specJson = (json \ "spec").as[JsObject]

    specJson.keys shouldBe Set("schedule")
  }

  it should "support status with external types" in {
    import skuber.examples.kronjob.KronJobResourceApplyConfigs.*
    import java.time.{ZoneOffset, ZonedDateTime}

    val now = ZonedDateTime.of(2026, 5, 3, 12, 0, 0, 0, ZoneOffset.UTC)
    val ac = KronJobApplyConfig("my-kronjob")
      .withStatus(StatusApplyConfig().withLastScheduleTime(now))

    val json = Json.toJson(ac)

    (json \ "status" \ "lastScheduleTime").as[String] shouldBe "2026-05-03T12:00:00Z"
    (json \ "status" \ "active").toOption shouldBe None
    (json \ "status" \ "lastSuccessfulTime").toOption shouldBe None
  }

  // ==================== @noApplyConfigGen ====================

  "@noApplyConfigGen" should "prevent apply config generation" in {
    val srcManagedDir = java.nio.file.Paths.get(
      sys.props.getOrElse("user.dir", "."),
      "examples", "target"
    ).toFile

    def findFile(dir: java.io.File, name: String): Option[java.io.File] =
      if !dir.exists then None
      else
        val found = dir.listFiles.toList.flatMap { f =>
          if f.isDirectory then findFile(f, name)
          else if f.getName == name then Some(f)
          else None
        }
        found.headOption

    findFile(srcManagedDir, "NoGenResourceApplyConfigs.scala") shouldBe None
  }
