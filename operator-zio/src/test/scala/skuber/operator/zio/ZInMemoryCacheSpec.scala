package skuber.operator.zio

import zio.*
import zio.test.*
import zio.test.Assertion.*
import play.api.libs.json.{Format, Json, OFormat}
import skuber.model.{ObjectResource, ResourceDefinition, ResourceSpecification}
import skuber.operator.reconciler.NamespacedName

object ZInMemoryCacheSpec extends ZIOSpecDefault:

  def mkResource(ns: String, name: String, value: String = "v"): TestResource =
    TestResource(metadata = skuber.model.ObjectMeta(namespace = ns, name = name), spec = TestSpec(value))

  def spec = suite("ZInMemoryCache")(

    test("get returns None for missing resource") {
      for
        cache <- ZInMemoryCache.make
        result <- cache.get[TestResource](NamespacedName("ns", "missing"))
      yield assertTrue(result.isEmpty)
    },

    test("update then get returns the resource") {
      val r = mkResource("ns", "foo", "hello")
      for
        cache <- ZInMemoryCache.make
        _     <- cache.update(r)
        result <- cache.get[TestResource](NamespacedName("ns", "foo"))
      yield assertTrue(result.contains(r))
    },

    test("delete removes the resource") {
      val r = mkResource("ns", "foo")
      for
        cache <- ZInMemoryCache.make
        _     <- cache.update(r)
        _     <- cache.delete[TestResource](NamespacedName("ns", "foo"))
        result <- cache.get[TestResource](NamespacedName("ns", "foo"))
      yield assertTrue(result.isEmpty)
    },

    test("list returns all resources of the type") {
      val r1 = mkResource("ns", "a")
      val r2 = mkResource("ns", "b")
      for
        cache <- ZInMemoryCache.make
        _     <- cache.update(r1)
        _     <- cache.update(r2)
        items <- cache.list[TestResource]
      yield assertTrue(items.toSet == Set(r1, r2))
    },

    test("update is idempotent — second update overwrites first") {
      val old = mkResource("ns", "x", "old")
      val updated = mkResource("ns", "x", "new")
      for
        cache <- ZInMemoryCache.make
        _     <- cache.update(old)
        _     <- cache.update(updated)
        result <- cache.get[TestResource](NamespacedName("ns", "x"))
      yield assertTrue(result.contains(updated))
    },

    test("concurrent updates are safe") {
      val resources = (1 to 50).map(i => mkResource("ns", s"r$i", s"val$i")).toList
      for
        cache <- ZInMemoryCache.make
        _     <- ZIO.collectAllPar(resources.map(cache.update))
        items <- cache.list[TestResource]
      yield assertTrue(items.size == 50)
    },

    test("different resource kinds are isolated in the same cache") {
      val r = mkResource("ns", "foo", "v1")
      for
        cache <- ZInMemoryCache.make
        _     <- cache.update(r)
        // Deleting a different kind should not affect TestResource
        _     <- cache.delete[TestResource2](NamespacedName("ns", "foo"))
        result <- cache.get[TestResource](NamespacedName("ns", "foo"))
      yield assertTrue(result.contains(r))
    },

    test("delete on non-existent key is a no-op") {
      for
        cache <- ZInMemoryCache.make
        _     <- cache.delete[TestResource](NamespacedName("ns", "never-inserted"))
        result <- cache.get[TestResource](NamespacedName("ns", "never-inserted"))
      yield assertTrue(result.isEmpty)
    }
  )
