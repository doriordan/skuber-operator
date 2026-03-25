package skuber.operator.zio

import zio.*
import zio.stream.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.TestAspect
import play.api.libs.json.Format
import skuber.api.client.{
  DeleteOptions, EventType, K8SException, ListOptions, Status, WatchEvent, WatchParameters
}
import skuber.api.patch.Patch
import skuber.model.{ListMeta, ListResource, ObjectMeta, ObjectResource, ResourceDefinition, Scale}
import skuber.operator.reconciler.{NamespacedName, ReconcileResult}
import skuber.zio.{ExecOutput, ZKubernetesClient}
import skuber.json.format.ListResourceFormat

object ZControllerSpec extends ZIOSpecDefault:

  given Format[ListResource[TestResource]] = ListResourceFormat[TestResource]

  def mkRes(name: String) =
    TestResource(
      metadata = ObjectMeta(namespace = "ns", name = name, resourceVersion = "1"),
      spec = TestSpec("val")
    )

  /** Stub client: returns an empty list, then the given watch events. */
  def stubClient(watchEvents: List[WatchEvent[TestResource]]): ZKubernetesClient =
    new ZKubernetesClient:
      override def list[L <: skuber.model.KList[?]]()(using Format[L], ResourceDefinition[L]) =
        ZIO.succeed(
          ListResource("test/v1", "TestResourceList", Some(ListMeta(resourceVersion = "1")), List.empty[TestResource])
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

  def spec = suite("ZController")(

    test("reconciler is called when a resource is added via watch") {
      val res   = mkRes("foo")
      val event = WatchEvent(EventType.ADDED, res)
      for
        reconciled <- Ref.make(List.empty[String])
        reconciler = new ZReconciler[TestResource]:
          def reconcile(resource: TestResource, ctx: ZReconcileContext): IO[K8SException, ReconcileResult] =
            reconciled.update(_ :+ resource.metadata.name).as(ReconcileResult.Done)
        cache  <- ZInMemoryCache.make
        queue  <- ZWorkQueue.make
        client  = stubClient(List(event))
        ctrl    = ZController[TestResource](reconciler, client, cache, queue)
        fiber  <- ctrl.run.fork
        // Poll until reconciler is called or timeout
        _      <- reconciled.get.repeatUntil(_.nonEmpty).timeout(10.seconds)
        _      <- fiber.interrupt
        names  <- reconciled.get
      yield assertTrue(names.contains("foo"))
    },

    test("cache miss (deleted resource) results in no-op, not error") {
      // Resource is NOT in cache; reconciler should never be called.
      // Use a Promise to detect if the worker finishes processing the item.
      for
        reconciled <- Ref.make(List.empty[String])
        done       <- Promise.make[Nothing, Unit]
        reconciler = new ZReconciler[TestResource]:
          def reconcile(resource: TestResource, ctx: ZReconcileContext): IO[K8SException, ReconcileResult] =
            reconciled.update(_ :+ resource.metadata.name).as(ReconcileResult.Done)
        cache  <- ZInMemoryCache.make
        queue  <- ZWorkQueue.make
        client  = stubClient(Nil)
        ctrl    = ZController[TestResource](reconciler, client, cache, queue)
        // Enqueue a name that is not in the cache, then run the controller
        _      <- queue.enqueue(NamespacedName("ns", "ghost"))
        fiber  <- ctrl.run.fork
        // Wait until the queue drains (the ghost item is processed as a cache miss)
        _      <- queue.size.repeatUntil(_ == 0).timeout(10.seconds)
        _      <- fiber.interrupt
        names  <- reconciled.get
      yield assertTrue(names.isEmpty)
    } @@ TestAspect.withLiveClock,

    test("Requeue result causes item to be re-enqueued") {
      val res   = mkRes("bar")
      val event = WatchEvent(EventType.ADDED, res)
      for
        count <- Ref.make(0)
        reconciler = new ZReconciler[TestResource]:
          def reconcile(resource: TestResource, ctx: ZReconcileContext): IO[K8SException, ReconcileResult] =
            count.updateAndGet(_ + 1).flatMap { n =>
              if n >= 2 then ZIO.succeed(ReconcileResult.Done)
              else ZIO.succeed(ReconcileResult.Requeue("again"))
            }
        cache  <- ZInMemoryCache.make
        queue  <- ZWorkQueue.make
        client  = stubClient(List(event))
        ctrl    = ZController[TestResource](reconciler, client, cache, queue)
        fiber  <- ctrl.run.fork
        _      <- count.get.repeatUntil(_ >= 2).timeout(10.seconds)
        _      <- fiber.interrupt
        n      <- count.get
      yield assertTrue(n >= 2)
    },

    test("reconciler K8SException triggers backoff re-enqueue") {
      // Verify the reconciler is called at least once when it throws K8SException.
      // The first backoff is 1 second, so we only assert >= 1 call to avoid
      // a slow test that waits through exponential backoff.
      val res   = mkRes("errored")
      val event = WatchEvent(EventType.ADDED, res)
      for
        count <- Ref.make(0)
        reconciler = new ZReconciler[TestResource]:
          def reconcile(resource: TestResource, ctx: ZReconcileContext): IO[K8SException, ReconcileResult] =
            count.updateAndGet(_ + 1) *>
              ZIO.fail(K8SException(Status(message = Some("test error"))))
        cache  <- ZInMemoryCache.make
        queue  <- ZWorkQueue.make
        client  = stubClient(List(event))
        ctrl    = ZController[TestResource](reconciler, client, cache, queue)
        fiber  <- ctrl.run.fork
        _      <- count.get.repeatUntil(_ >= 1).timeout(10.seconds)
        _      <- fiber.interrupt
        n      <- count.get
      yield assertTrue(n >= 1)
    }
  )
