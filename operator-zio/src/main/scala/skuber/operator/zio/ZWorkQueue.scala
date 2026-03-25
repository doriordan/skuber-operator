package skuber.operator.zio

import zio.*
import skuber.operator.reconciler.NamespacedName

private case class WorkQueueState(
  pending: Set[NamespacedName],
  queue: Vector[NamespacedName],
  waiters: Vector[Promise[Nothing, NamespacedName]]
)

private object WorkQueueState:
  val empty: WorkQueueState = WorkQueueState(Set.empty, Vector.empty, Vector.empty)

class ZWorkQueue private (stateRef: Ref[WorkQueueState]):

  def enqueue(name: NamespacedName): UIO[Unit] =
    stateRef.modify { state =>
      if state.pending.contains(name) then
        (ZIO.unit, state)
      else if state.waiters.nonEmpty then
        val waiter = state.waiters.head
        // Add to pending so a concurrent enqueue of the same name deduplicates
        // while the item is being processed by the waiting fiber.
        (waiter.succeed(name).unit, state.copy(
          pending = state.pending + name,
          waiters = state.waiters.tail
        ))
      else
        (ZIO.unit, state.copy(
          pending = state.pending + name,
          queue   = state.queue :+ name
        ))
    }.flatten

  def dequeue: UIO[NamespacedName] =
    Promise.make[Nothing, NamespacedName].flatMap { p =>
      stateRef.modify { state =>
        state.queue match
          case head +: tail =>
            // Normal path: take from queue and remove from pending immediately.
            (ZIO.succeed(head), state.copy(
              pending = state.pending - head,
              queue   = tail
            ))
          case _ =>
            // Empty queue: register waiter. When enqueue fulfills this promise,
            // the name will be in `pending`. We chain a pending cleanup so that
            // the name is removed from pending once this fiber has received it —
            // re-opening the deduplication window for subsequent re-enqueues.
            val effect = p.await.flatMap { name =>
              stateRef.update(s => s.copy(pending = s.pending - name)).as(name)
            }
            (effect, state.copy(waiters = state.waiters :+ p))
      }.flatten
    }

  /** Returns the number of items currently pending (enqueued or being processed).
   *  Includes items being actively processed by waiting fibers, making this
   *  a reliable deduplication and backpressure signal. */
  def size: UIO[Int] =
    stateRef.get.map(_.pending.size)

object ZWorkQueue:
  def make: UIO[ZWorkQueue] =
    Ref.make(WorkQueueState.empty).map(new ZWorkQueue(_))
