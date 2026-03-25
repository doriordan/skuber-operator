package skuber.operator.zio

import zio.*
import zio.stream.*
import zio.test.*
import zio.test.Assertion.*
import play.api.libs.json.{Format, Json, OFormat}
import skuber.api.client.{K8SException, WatchEvent, WatchParameters, EventType, DeleteOptions, ListOptions}
import skuber.api.patch.Patch
import skuber.model.{ListResource, ObjectMeta, ObjectResource, ResourceDefinition, Scale}
import skuber.operator.reconciler.NamespacedName
import skuber.zio.{ExecOutput, ZKubernetesClient}
import skuber.json.format.ListResourceFormat

object ZReflectorSpec extends ZIOSpecDefault:

  given Format[ListResource[TestResource]] = ListResourceFormat[TestResource]

  def mkRes(name: String, rv: String = "1") =
    TestResource(metadata = ObjectMeta(namespace = "ns", name = name, resourceVersion = rv), spec = TestSpec("val"))

  // Stub client that returns a fixed list and then the given watch events
  def stubClient(listItems: List[TestResource], watchEvents: List[WatchEvent[TestResource]]): ZKubernetesClient =
    new ZKubernetesClient:
      override def list[L <: skuber.model.KList[?]]()(using Format[L], ResourceDefinition[L]) =
        ZIO.succeed(
          ListResource("test/v1", "TestResourceList", Some(skuber.model.ListMeta(resourceVersion = "42")), listItems)
            .asInstanceOf[L]
        )
      override def watch[O <: ObjectResource](params: WatchParameters)(using Format[O], ResourceDefinition[O]) =
        ZStream.fromIterable(watchEvents).asInstanceOf[ZStream[Any, K8SException, WatchEvent[O]]]
      override def get[O <: ObjectResource](name: String)(using Format[O], ResourceDefinition[O]) = ZIO.die(new Exception("not expected"))
      override def getOption[O <: ObjectResource](name: String)(using Format[O], ResourceDefinition[O]) = ZIO.die(new Exception("not expected"))
      override def create[O <: ObjectResource](obj: O)(using Format[O], ResourceDefinition[O]) = ZIO.die(new Exception("not expected"))
      override def update[O <: ObjectResource](obj: O)(using Format[O], ResourceDefinition[O]) = ZIO.die(new Exception("not expected"))
      override def delete[O <: ObjectResource](name: String, gracePeriodSeconds: Int)(using ResourceDefinition[O]) = ZIO.die(new Exception("not expected"))
      override def deleteWithOptions[O <: ObjectResource](name: String, options: DeleteOptions)(using ResourceDefinition[O]) = ZIO.die(new Exception("not expected"))
      override def listSelected[L <: skuber.model.KList[?]](labelSelector: skuber.model.LabelSelector)(using Format[L], ResourceDefinition[L]) = ZIO.die(new Exception("not expected"))
      override def listWithOptions[L <: skuber.model.KList[?]](options: ListOptions)(using Format[L], ResourceDefinition[L]) = ZIO.die(new Exception("not expected"))
      override def updateStatus[O <: ObjectResource](obj: O)(using Format[O], ResourceDefinition[O], skuber.model.HasStatusSubresource[O]) = ZIO.die(new Exception("not expected"))
      override def getScale[O <: ObjectResource](name: String)(using ResourceDefinition[O], Scale.SubresourceSpec[O]) = ZIO.die(new Exception("not expected"))
      override def updateScale[O <: ObjectResource](name: String, scale: Scale)(using ResourceDefinition[O], Scale.SubresourceSpec[O]) = ZIO.die(new Exception("not expected"))
      override def patch[P <: Patch, O <: ObjectResource](name: String, patchData: P, namespace: Option[String])(using play.api.libs.json.Writes[P], Format[O], ResourceDefinition[O]) = ZIO.die(new Exception("not expected"))
      override def getPodLogStream(name: String, queryParams: skuber.model.Pod.LogQueryParams, namespace: Option[String]) = ZStream.die(new Exception("not expected"))
      override def exec(podName: String, command: Seq[String], containerName: Option[String], stdin: Option[ZStream[Any, Nothing, String]], tty: Boolean) = ZStream.die(new Exception("not expected"))
      override def getServerAPIVersions = ZIO.die(new Exception("not expected"))
      override def usingNamespace(namespace: String) = this

  def spec = suite("ZReflector")(

    test("initial list seeds the cache") {
      val res = mkRes("existing")
      val client = stubClient(List(res), Nil)
      for
        cache <- ZInMemoryCache.make
        queue <- ZWorkQueue.make
        _     <- ZReflector.stream[TestResource](client, cache, queue).runDrain
        cached <- cache.get[TestResource](NamespacedName("ns", "existing"))
      yield assertTrue(cached.isDefined)
    },

    test("ADDED event updates cache and enqueues name") {
      val added = mkRes("new-one")
      val event = WatchEvent(EventType.ADDED, added)
      val client = stubClient(Nil, List(event))
      for
        cache <- ZInMemoryCache.make
        queue <- ZWorkQueue.make
        _     <- ZReflector.stream[TestResource](client, cache, queue).runDrain
        cached  <- cache.get[TestResource](NamespacedName("ns", "new-one"))
        queueSz <- queue.size
      yield assertTrue(cached.isDefined && queueSz == 1)
    },

    test("DELETED event removes from cache and enqueues name") {
      val existing = mkRes("gone")
      val event    = WatchEvent(EventType.DELETED, existing)
      val client   = stubClient(List(existing), List(event))
      for
        cache <- ZInMemoryCache.make
        queue <- ZWorkQueue.make
        _     <- ZReflector.stream[TestResource](client, cache, queue).runDrain
        cached  <- cache.get[TestResource](NamespacedName("ns", "gone"))
        queueSz <- queue.size
      yield assertTrue(cached.isEmpty && queueSz == 1)
    },

    test("watch is started with resourceVersion from list") {
      for
        capturedRv <- Ref.make[Option[String]](None)
        client = new ZKubernetesClient:
          override def list[L <: skuber.model.KList[?]]()(using Format[L], ResourceDefinition[L]) =
            ZIO.succeed(
              ListResource("test/v1", "TestResourceList", Some(skuber.model.ListMeta(resourceVersion = "42")), Nil)
                .asInstanceOf[L]
            )
          override def watch[O <: ObjectResource](params: WatchParameters)(using Format[O], ResourceDefinition[O]) =
            ZStream.fromZIO(capturedRv.set(params.resourceVersion)).drain ++
              ZStream.empty.asInstanceOf[ZStream[Any, K8SException, WatchEvent[O]]]
          override def get[O <: ObjectResource](name: String)(using Format[O], ResourceDefinition[O]) = ZIO.die(new Exception("not expected"))
          override def getOption[O <: ObjectResource](name: String)(using Format[O], ResourceDefinition[O]) = ZIO.die(new Exception("not expected"))
          override def create[O <: ObjectResource](obj: O)(using Format[O], ResourceDefinition[O]) = ZIO.die(new Exception("not expected"))
          override def update[O <: ObjectResource](obj: O)(using Format[O], ResourceDefinition[O]) = ZIO.die(new Exception("not expected"))
          override def delete[O <: ObjectResource](name: String, gracePeriodSeconds: Int)(using ResourceDefinition[O]) = ZIO.die(new Exception("not expected"))
          override def deleteWithOptions[O <: ObjectResource](name: String, options: DeleteOptions)(using ResourceDefinition[O]) = ZIO.die(new Exception("not expected"))
          override def listSelected[L <: skuber.model.KList[?]](labelSelector: skuber.model.LabelSelector)(using Format[L], ResourceDefinition[L]) = ZIO.die(new Exception("not expected"))
          override def listWithOptions[L <: skuber.model.KList[?]](options: ListOptions)(using Format[L], ResourceDefinition[L]) = ZIO.die(new Exception("not expected"))
          override def updateStatus[O <: ObjectResource](obj: O)(using Format[O], ResourceDefinition[O], skuber.model.HasStatusSubresource[O]) = ZIO.die(new Exception("not expected"))
          override def getScale[O <: ObjectResource](name: String)(using ResourceDefinition[O], Scale.SubresourceSpec[O]) = ZIO.die(new Exception("not expected"))
          override def updateScale[O <: ObjectResource](name: String, scale: Scale)(using ResourceDefinition[O], Scale.SubresourceSpec[O]) = ZIO.die(new Exception("not expected"))
          override def patch[P <: Patch, O <: ObjectResource](name: String, patchData: P, namespace: Option[String])(using play.api.libs.json.Writes[P], Format[O], ResourceDefinition[O]) = ZIO.die(new Exception("not expected"))
          override def getPodLogStream(name: String, queryParams: skuber.model.Pod.LogQueryParams, namespace: Option[String]) = ZStream.die(new Exception("not expected"))
          override def exec(podName: String, command: Seq[String], containerName: Option[String], stdin: Option[ZStream[Any, Nothing, String]], tty: Boolean) = ZStream.die(new Exception("not expected"))
          override def getServerAPIVersions = ZIO.die(new Exception("not expected"))
          override def usingNamespace(namespace: String) = this
        cache <- ZInMemoryCache.make
        queue <- ZWorkQueue.make
        _     <- ZReflector.stream[TestResource](client, cache, queue).runDrain
        rv    <- capturedRv.get
      yield assertTrue(rv == Some("42"))
    }
  )
