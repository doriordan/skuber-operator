package skuber.operator.zio

import zio.*
import zio.test.*
import zio.test.Assertion.*
import skuber.operator.reconciler.NamespacedName

object ZWorkQueueSpec extends ZIOSpecDefault:

  def nn(name: String) = NamespacedName("ns", name)

  def spec = suite("ZWorkQueue")(

    test("enqueue then size returns 1") {
      for
        q <- ZWorkQueue.make
        _ <- q.enqueue(nn("foo"))
        s <- q.size
      yield assertTrue(s == 1)
    },

    test("duplicate enqueue is a no-op") {
      for
        q <- ZWorkQueue.make
        _ <- q.enqueue(nn("foo"))
        _ <- q.enqueue(nn("foo"))
        s <- q.size
      yield assertTrue(s == 1)
    },

    test("dequeue returns enqueued item and removes from pending") {
      for
        q    <- ZWorkQueue.make
        _    <- q.enqueue(nn("foo"))
        item <- q.dequeue
        s    <- q.size
      yield assertTrue(item == nn("foo") && s == 0)
    },

    test("dequeue blocks until enqueue is called") {
      for
        q      <- ZWorkQueue.make
        fiber  <- q.dequeue.fork          // blocks — nothing in queue yet
        _      <- ZIO.yieldNow            // let fiber block
        _      <- q.enqueue(nn("bar"))    // unblocks the fiber
        result <- fiber.join
      yield assertTrue(result == nn("bar"))
    },

    test("items are dequeued in FIFO order") {
      for
        q  <- ZWorkQueue.make
        _  <- q.enqueue(nn("a"))
        _  <- q.enqueue(nn("b"))
        _  <- q.enqueue(nn("c"))
        r1 <- q.dequeue
        r2 <- q.dequeue
        r3 <- q.dequeue
      yield assertTrue(r1 == nn("a") && r2 == nn("b") && r3 == nn("c"))
    },

    test("after dequeue, same name can be re-enqueued") {
      for
        q    <- ZWorkQueue.make
        _    <- q.enqueue(nn("foo"))
        _    <- q.dequeue
        _    <- q.enqueue(nn("foo"))  // should succeed (no longer pending)
        s    <- q.size
      yield assertTrue(s == 1)
    },

    test("concurrent enqueues deduplicate correctly") {
      for
        q <- ZWorkQueue.make
        // 20 concurrent enqueues of the same name
        _ <- ZIO.collectAllPar(List.fill(20)(q.enqueue(nn("x"))))
        s <- q.size
      yield assertTrue(s == 1)
    },

    test("multiple waiting dequeues are both fulfilled") {
      for
        q      <- ZWorkQueue.make
        fiber1 <- q.dequeue.fork
        fiber2 <- q.dequeue.fork
        _      <- ZIO.yieldNow
        _      <- q.enqueue(nn("first"))
        _      <- q.enqueue(nn("second"))
        r1     <- fiber1.join
        r2     <- fiber2.join
      yield assertTrue(Set(r1, r2) == Set(nn("first"), nn("second")))
    },

    test("pending is cleared after waiter receives item") {
      // When enqueue fulfills a waiter, x enters pending. After the waiter
      // fiber completes its dequeue cleanup, x must be removed from pending
      // so it can be re-enqueued on future events.
      for
        q     <- ZWorkQueue.make
        fiber <- q.dequeue.fork
        _     <- ZIO.yieldNow
        _     <- q.enqueue(nn("x"))
        result <- fiber.join
        s     <- q.size
      yield assertTrue(result == nn("x") && s == 0)
    }
  )
